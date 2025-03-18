/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.txmetadata;

import java.time.Instant;
import java.util.Objects;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.NotThreadSafe;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.data.Envelope;
import io.debezium.function.BlockingConsumer;
import io.debezium.pipeline.source.spi.EventMetadataProvider;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.schema.SchemaFactory;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.util.Loggings;

/**
 * The class has externalized its state in {@link TransactionContext} context class so it can be stored in and recovered from offsets.
 * The class receives all processed events and keeps the transaction tracking depending on transaction id.
 * Upon transaction change the metadata events are delivered to a dedicated topic informing about {@code START/END} of the transaction,
 * including transaction id and in case of {@code END} event the amount of events generated by the transaction.
 * <p>
 * Every event seen has its {@code source} block enriched to contain
 *
 * <ul>
 * <li>transaction id</li>
 * <li>the total event order in the transaction</li>
 * <li>the order of event per table/collection source in the transaction</li>
 * </ul>
 *
 * @author Jiri Pechanec
 */
@NotThreadSafe
public class TransactionMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionMonitor.class);
    public static final Schema TRANSACTION_BLOCK_SCHEMA = SchemaFactory.get().transactionBlockSchema();
    private final EventMetadataProvider eventMetadataProvider;
    private final String topicName;
    private final BlockingConsumer<SourceRecord> sender;
    private final CommonConnectorConfig connectorConfig;
    private final TransactionStructMaker transactionStructMaker;

    // Following instance variables are kept for backward compatibility with connectors that override TransactionMonitor
    protected final Schema transactionKeySchema;
    protected final String DEBEZIUM_TRANSACTION_ID_KEY = TransactionStructMaker.DEBEZIUM_TRANSACTION_ID_KEY;

    public TransactionMonitor(CommonConnectorConfig connectorConfig, EventMetadataProvider eventMetadataProvider,
                              SchemaNameAdjuster schemaNameAdjuster, BlockingConsumer<SourceRecord> sender,
                              String topicName) {
        Objects.requireNonNull(eventMetadataProvider);

        transactionStructMaker = connectorConfig.getTransactionMetadataFactory().getTransactionStructMaker();
        transactionKeySchema = transactionStructMaker.getTransactionKeySchema();

        this.topicName = topicName;
        this.eventMetadataProvider = eventMetadataProvider;
        this.sender = sender;
        this.connectorConfig = connectorConfig;
    }

    public void dataEvent(Partition partition, DataCollectionId source, OffsetContext offset, Object key, Struct value) throws InterruptedException {
        if (!connectorConfig.shouldProvideTransactionMetadata()) {
            return;
        }
        final TransactionContext transactionContext = offset.getTransactionContext();

        final String txId = eventMetadataProvider.getTransactionId(source, offset, key, value);
        final TransactionInfo transactionInfo = eventMetadataProvider.getTransactionInfo(source, offset, key, value);
        if (txId == null) {
            if (LOGGER.isTraceEnabled()) {
                Loggings.logTraceAndTraceRecord(LOGGER, key, "Event '{}' has no transaction id", eventMetadataProvider.toSummaryString(source, offset, null, value));
            }
            // Introduced for MongoDB, transactions are optional so non-transactional event should
            // commit transaction
            if (transactionContext.isTransactionInProgress()) {
                LOGGER.trace("Transaction was in progress, executing implicit transaction commit");
                endTransaction(partition, offset, eventMetadataProvider.getEventTimestamp(source, offset, key, value));
            }
            return;
        }

        if (!transactionContext.isTransactionInProgress()) {
            transactionContext.beginTransaction(transactionInfo);
            beginTransaction(partition, offset, eventMetadataProvider.getEventTimestamp(source, offset, key, value));
        }
        else if (!transactionContext.getTransactionId().equals(txId)) {
            endTransaction(partition, offset, eventMetadataProvider.getEventTimestamp(source, offset, key, value));
            transactionContext.endTransaction();
            transactionContext.beginTransaction(transactionInfo);
            beginTransaction(partition, offset, eventMetadataProvider.getEventTimestamp(source, offset, key, value));
        }
        transactionEvent(offset, source, value);
    }

    public void transactionComittedEvent(Partition partition, OffsetContext offset, Instant timestamp) throws InterruptedException {
        if (!connectorConfig.shouldProvideTransactionMetadata()) {
            return;
        }
        if (offset.getTransactionContext().isTransactionInProgress()) {
            endTransaction(partition, offset, timestamp);
        }
        offset.getTransactionContext().endTransaction();
    }

    public void transactionStartedEvent(Partition partition, TransactionInfo transactionInfo, OffsetContext offset, Instant timestamp) throws InterruptedException {
        if (!connectorConfig.shouldProvideTransactionMetadata()) {
            return;
        }
        offset.getTransactionContext().beginTransaction(transactionInfo);
        beginTransaction(partition, offset, timestamp);
    }

    protected Struct prepareTxKey(OffsetContext offsetContext) {
        final Struct key = transactionStructMaker.buildTransactionKey(offsetContext);
        return key;
    }

    protected Struct prepareTxBeginValue(OffsetContext offsetContext, Instant timestamp) {
        final Struct value = transactionStructMaker.buildBeginTransactionValue(offsetContext, timestamp);
        return value;
    }

    protected Struct prepareTxEndValue(OffsetContext offsetContext, Instant timestamp) {
        final Struct value = transactionStructMaker.buildEndTransactionValue(offsetContext, timestamp);
        return value;
    }

    protected Struct prepareTxStruct(OffsetContext offsetContext, long dataCollectionEventOrder, Struct value) {
        final Struct txStruct = transactionStructMaker.addTransactionBlock(offsetContext, dataCollectionEventOrder, value);
        return txStruct;
    }

    private void transactionEvent(OffsetContext offsetContext, DataCollectionId source, Struct value) {
        final long dataCollectionEventOrder = offsetContext.getTransactionContext().event(source);
        if (value == null) {
            LOGGER.debug("Event with key {} without value. Cannot enrich source block.");
            return;
        }
        final Struct txStruct = prepareTxStruct(offsetContext, dataCollectionEventOrder, value);
        value.put(Envelope.FieldName.TRANSACTION, txStruct);
    }

    private void beginTransaction(Partition partition, OffsetContext offsetContext, Instant timestamp) throws InterruptedException {
        final Struct key = prepareTxKey(offsetContext);
        final Struct value = prepareTxBeginValue(offsetContext, timestamp);
        sender.accept(new SourceRecord(partition.getSourcePartition(), offsetContext.getOffset(),
                topicName, null, key.schema(), key, value.schema(), value));
    }

    private void endTransaction(Partition partition, OffsetContext offsetContext, Instant timestamp) throws InterruptedException {
        final Struct key = prepareTxKey(offsetContext);
        final Struct value = prepareTxEndValue(offsetContext, timestamp);
        sender.accept(new SourceRecord(partition.getSourcePartition(), offsetContext.getOffset(),
                topicName, null, key.schema(), key, value.schema(), value));
    }
}
