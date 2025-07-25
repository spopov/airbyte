/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.mysql

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.airbyte.cdk.command.OpaqueStateValue
import io.airbyte.cdk.discover.Field
import io.airbyte.cdk.jdbc.JdbcConnectionFactory
import io.airbyte.cdk.read.And
import io.airbyte.cdk.read.DefaultJdbcStreamState
import io.airbyte.cdk.read.Equal
import io.airbyte.cdk.read.From
import io.airbyte.cdk.read.FromSample
import io.airbyte.cdk.read.Greater
import io.airbyte.cdk.read.GreaterOrEqual
import io.airbyte.cdk.read.JdbcCursorPartition
import io.airbyte.cdk.read.JdbcNonResumablePartitionReader
import io.airbyte.cdk.read.JdbcPartition
import io.airbyte.cdk.read.JdbcPartitionFactory
import io.airbyte.cdk.read.JdbcPartitionsCreator
import io.airbyte.cdk.read.JdbcPartitionsCreatorFactory
import io.airbyte.cdk.read.JdbcSharedState
import io.airbyte.cdk.read.JdbcSplittablePartition
import io.airbyte.cdk.read.JdbcStreamState
import io.airbyte.cdk.read.Lesser
import io.airbyte.cdk.read.LesserOrEqual
import io.airbyte.cdk.read.Limit
import io.airbyte.cdk.read.MODE_PROPERTY
import io.airbyte.cdk.read.NoWhere
import io.airbyte.cdk.read.Or
import io.airbyte.cdk.read.OrderBy
import io.airbyte.cdk.read.PartitionReader
import io.airbyte.cdk.read.Sample
import io.airbyte.cdk.read.SelectColumnMaxValue
import io.airbyte.cdk.read.SelectColumns
import io.airbyte.cdk.read.SelectQuery
import io.airbyte.cdk.read.SelectQueryGenerator
import io.airbyte.cdk.read.SelectQuerySpec
import io.airbyte.cdk.read.Stream
import io.airbyte.cdk.read.Where
import io.airbyte.cdk.read.WhereClauseLeafNode
import io.airbyte.cdk.read.WhereClauseNode
import io.airbyte.cdk.read.optimize
import io.airbyte.cdk.util.Jsons
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Primary
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton
import kotlin.random.Random

/** Base class for default implementations of [JdbcPartition] for non resumable partitions. */
sealed class MySqlSourceJdbcPartition(
    val selectQueryGenerator: SelectQueryGenerator,
    streamState: DefaultJdbcStreamState,
) : JdbcPartition<DefaultJdbcStreamState> {
    val stream: Stream = streamState.stream
    val from = From(stream.name, stream.namespace)

    override val nonResumableQuery: SelectQuery
        get() = selectQueryGenerator.generate(nonResumableQuerySpec.optimize())

    open val nonResumableQuerySpec = SelectQuerySpec(SelectColumns(stream.fields), from)

    override fun samplingQuery(sampleRateInvPow2: Int): SelectQuery {
        val sampleSize: Int = streamState.sharedState.maxSampleSize
        val querySpec =
            SelectQuerySpec(
                SelectColumns(stream.fields),
                From(stream.name, stream.namespace),
                limit = Limit(sampleSize.toLong()),
            )
        return selectQueryGenerator.generate(querySpec.optimize())
    }
}

/** Default implementation of a [JdbcPartition] for an unsplittable snapshot partition. */
class MySqlSourceJdbcNonResumableSnapshotPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
) : MySqlSourceJdbcPartition(selectQueryGenerator, streamState) {

    override val completeState: OpaqueStateValue = MySqlSourceJdbcStreamStateValue.snapshotCompleted
}

/**
 * Default implementation of a [JdbcPartition] for an non resumable snapshot partition preceding a
 * cursor-based incremental sync.
 */
class MySqlSourceJdbcNonResumableSnapshotWithCursorPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    val cursor: Field,
) :
    MySqlSourceJdbcPartition(selectQueryGenerator, streamState),
    JdbcCursorPartition<DefaultJdbcStreamState> {

    override val completeState: OpaqueStateValue
        get() =
            MySqlSourceJdbcStreamStateValue.cursorIncrementalCheckpoint(
                cursor,
                cursorCheckpoint = streamState.cursorUpperBound!!,
                streamState.stream,
            )

    override val cursorUpperBoundQuery: SelectQuery
        get() = selectQueryGenerator.generate(cursorUpperBoundQuerySpec.optimize())

    val cursorUpperBoundQuerySpec = SelectQuerySpec(SelectColumnMaxValue(cursor), from)
}

/** Base class for default implementations of [JdbcPartition] for partitions. */
sealed class MySqlSourceJdbcResumablePartition(
    selectQueryGenerator: SelectQueryGenerator,
    streamState: DefaultJdbcStreamState,
    val checkpointColumns: List<Field>,
) :
    MySqlSourceJdbcPartition(selectQueryGenerator, streamState),
    JdbcSplittablePartition<DefaultJdbcStreamState> {
    abstract val lowerBound: List<JsonNode>?
    abstract val upperBound: List<JsonNode>?

    override val nonResumableQuery: SelectQuery
        get() = selectQueryGenerator.generate(nonResumableQuerySpec.optimize())

    override val nonResumableQuerySpec: SelectQuerySpec
        get() = SelectQuerySpec(SelectColumns(stream.fields), from, where)

    override fun resumableQuery(limit: Long): SelectQuery {
        val querySpec =
            SelectQuerySpec(
                SelectColumns((stream.fields + checkpointColumns).distinct()),
                from,
                where,
                OrderBy(checkpointColumns),
                Limit(limit),
            )
        return selectQueryGenerator.generate(querySpec.optimize())
    }

    override fun samplingQuery(sampleRateInvPow2: Int): SelectQuery {
        val sampleSize: Int = streamState.sharedState.maxSampleSize
        val querySpec =
            SelectQuerySpec(
                SelectColumns(stream.fields + checkpointColumns),
                FromSample(stream.name, stream.namespace, sampleRateInvPow2, sampleSize),
                NoWhere,
                OrderBy(checkpointColumns),
                Limit(sampleSize.toLong())
            )
        return selectQueryGenerator.generate(querySpec.optimize())
    }

    val where: Where
        get() {
            val zippedLowerBound: List<Pair<Field, JsonNode>> =
                lowerBound?.let { checkpointColumns.zip(it) } ?: listOf()
            val lowerBoundDisj: List<WhereClauseNode> =
                zippedLowerBound.mapIndexed { idx: Int, (gtCol: Field, gtValue: JsonNode) ->
                    val lastLeaf: WhereClauseLeafNode =
                        if (isLowerBoundIncluded && idx == checkpointColumns.size - 1) {
                            GreaterOrEqual(gtCol, gtValue)
                        } else {
                            Greater(gtCol, gtValue)
                        }
                    And(
                        zippedLowerBound.take(idx).map { (eqCol: Field, eqValue: JsonNode) ->
                            Equal(eqCol, eqValue)
                        } + listOf(lastLeaf),
                    )
                }
            val zippedUpperBound: List<Pair<Field, JsonNode>> =
                upperBound?.let { checkpointColumns.zip(it) } ?: listOf()
            val upperBoundDisj: List<WhereClauseNode> =
                zippedUpperBound.mapIndexed { idx: Int, (leqCol: Field, leqValue: JsonNode) ->
                    val lastLeaf: WhereClauseLeafNode =
                        if (idx < zippedUpperBound.size - 1) {
                            Lesser(leqCol, leqValue)
                        } else {
                            LesserOrEqual(leqCol, leqValue)
                        }
                    And(
                        zippedUpperBound.take(idx).map { (eqCol: Field, eqValue: JsonNode) ->
                            Equal(eqCol, eqValue)
                        } + listOf(lastLeaf),
                    )
                }
            return Where(And(Or(lowerBoundDisj), Or(upperBoundDisj)))
        }

    open val isLowerBoundIncluded: Boolean = false
}

/** RFR for cursor based read. */
class MySqlSourceJdbcRfrSnapshotPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    primaryKey: List<Field>,
    override val lowerBound: List<JsonNode>?,
    override val upperBound: List<JsonNode>?,
) : MySqlSourceJdbcResumablePartition(selectQueryGenerator, streamState, primaryKey) {
    override val isLowerBoundIncluded: Boolean = lowerBound != null

    // TODO: this needs to reflect lastRecord. Complete state needs to have last primary key value
    // in RFR case.
    override val completeState: OpaqueStateValue
        get() =
            when (upperBound) {
                null -> MySqlSourceJdbcStreamStateValue.snapshotCompleted
                else ->
                    MySqlSourceJdbcStreamStateValue.snapshotCheckpoint(
                        primaryKey = checkpointColumns,
                        primaryKeyCheckpoint = checkpointColumns.map { upperBound.get(0) },
                    )
            }

    override fun incompleteState(lastRecord: ObjectNode): OpaqueStateValue =
        MySqlSourceJdbcStreamStateValue.snapshotCheckpoint(
            primaryKey = checkpointColumns,
            primaryKeyCheckpoint = checkpointColumns.map { lastRecord[it.id] ?: Jsons.nullNode() },
        )
}

typealias MySqlSourceJdbcSplittableRfrSnapshotPartition = MySqlSourceJdbcRfrSnapshotPartition

/** RFR for CDC. */
class MySqlSourceJdbcCdcRfrSnapshotPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    primaryKey: List<Field>,
    override val lowerBound: List<JsonNode>?,
    override val upperBound: List<JsonNode>?,
) : MySqlSourceJdbcResumablePartition(selectQueryGenerator, streamState, primaryKey) {
    override val completeState: OpaqueStateValue
        get() =
            MySqlSourceCdcInitialSnapshotStateValue.snapshotCheckpoint(
                primaryKey = checkpointColumns,
                primaryKeyCheckpoint =
                    checkpointColumns.map { upperBound?.get(0) ?: Jsons.nullNode() },
            )

    override fun incompleteState(lastRecord: ObjectNode): OpaqueStateValue =
        MySqlSourceCdcInitialSnapshotStateValue.snapshotCheckpoint(
            primaryKey = checkpointColumns,
            primaryKeyCheckpoint = checkpointColumns.map { lastRecord[it.id] ?: Jsons.nullNode() },
        )
}

// typealias MySqlSourceJdbcSplittableCdcRfrSnapshotPartition = MySqlSourceJdbcCdcSnapshotPartition
class MySqlSourceJdbcSplittableCdcRfrSnapshotPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    primaryKey: List<Field>,
    override val lowerBound: List<JsonNode>?,
    override val upperBound: List<JsonNode>?,
) : MySqlSourceJdbcResumablePartition(selectQueryGenerator, streamState, primaryKey) {
    override val completeState: OpaqueStateValue
        get() = MySqlSourceCdcInitialSnapshotStateValue.getSnapshotCompletedState(stream)

    override fun incompleteState(lastRecord: ObjectNode): OpaqueStateValue =
        MySqlSourceCdcInitialSnapshotStateValue.snapshotCheckpoint(
            primaryKey = checkpointColumns,
            primaryKeyCheckpoint = checkpointColumns.map { lastRecord[it.id] ?: Jsons.nullNode() },
        )
}

/**
 * Implementation of a [JdbcPartition] for a CDC snapshot partition. Used for incremental CDC
 * initial sync.
 */
class MySqlSourceJdbcCdcSnapshotPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    primaryKey: List<Field>,
    override val lowerBound: List<JsonNode>?
) : MySqlSourceJdbcResumablePartition(selectQueryGenerator, streamState, primaryKey) {
    override val upperBound: List<JsonNode>? = null
    override val completeState: OpaqueStateValue
        get() = MySqlSourceCdcInitialSnapshotStateValue.getSnapshotCompletedState(stream)

    override fun incompleteState(lastRecord: ObjectNode): OpaqueStateValue =
        MySqlSourceCdcInitialSnapshotStateValue.snapshotCheckpoint(
            primaryKey = checkpointColumns,
            primaryKeyCheckpoint = checkpointColumns.map { lastRecord[it.id] ?: Jsons.nullNode() },
        )
}

/**
 * Default implementation of a [JdbcPartition] for a splittable partition involving cursor columns.
 */
sealed class MySqlSourceJdbcCursorPartition(
    selectQueryGenerator: SelectQueryGenerator,
    streamState: DefaultJdbcStreamState,
    checkpointColumns: List<Field>,
    val cursor: Field,
    private val explicitCursorUpperBound: JsonNode?,
) :
    MySqlSourceJdbcResumablePartition(selectQueryGenerator, streamState, checkpointColumns),
    JdbcCursorPartition<DefaultJdbcStreamState> {

    val cursorUpperBound: JsonNode
        get() = explicitCursorUpperBound ?: streamState.cursorUpperBound ?: Jsons.nullNode()

    override val cursorUpperBoundQuery: SelectQuery
        get() = selectQueryGenerator.generate(cursorUpperBoundQuerySpec.optimize())

    val cursorUpperBoundQuerySpec = SelectQuerySpec(SelectColumnMaxValue(cursor), from)
}

/**
 * Default implementation of a [JdbcPartition] for a splittable snapshot partition preceding a
 * cursor-based incremental sync.
 */
class MySqlSourceJdbcSnapshotWithCursorPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    primaryKey: List<Field>,
    override val lowerBound: List<JsonNode>?,
    cursor: Field,
    cursorUpperBound: JsonNode?,
) :
    MySqlSourceJdbcCursorPartition(
        selectQueryGenerator,
        streamState,
        primaryKey,
        cursor,
        cursorUpperBound
    ) {
    // UpperBound is not used because the partition is not splittable.
    override val upperBound: List<JsonNode>? = null

    override val completeState: OpaqueStateValue
        get() =
            MySqlSourceJdbcStreamStateValue.cursorIncrementalCheckpoint(
                cursor,
                cursorUpperBound,
                stream,
            )

    override fun incompleteState(lastRecord: ObjectNode): OpaqueStateValue =
        MySqlSourceJdbcStreamStateValue.snapshotWithCursorCheckpoint(
            primaryKey = checkpointColumns,
            primaryKeyCheckpoint = checkpointColumns.map { lastRecord[it.id] ?: Jsons.nullNode() },
            cursor,
            stream,
        )
}

class MySqlSourceJdbcSplittableSnapshotWithCursorPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    primaryKey: List<Field>,
    override val lowerBound: List<JsonNode>?,
    override val upperBound: List<JsonNode>?,
    cursor: Field,
    cursorUpperBound: JsonNode?,
) :
    MySqlSourceJdbcCursorPartition(
        selectQueryGenerator,
        streamState,
        primaryKey,
        cursor,
        cursorUpperBound
    ) {
    override fun incompleteState(lastRecord: ObjectNode): OpaqueStateValue =
        MySqlSourceJdbcStreamStateValue.snapshotWithCursorCheckpoint(
            checkpointColumns,
            checkpointColumns.map { lastRecord[it.id] ?: Jsons.nullNode() },
            cursor,
            stream,
        )

    override val completeState: OpaqueStateValue
        get() =
            when (upperBound) {
                null ->
                    MySqlSourceJdbcStreamStateValue.cursorIncrementalCheckpoint(
                        cursor,
                        cursorUpperBound,
                        stream,
                    )
                else ->
                    MySqlSourceJdbcStreamStateValue.snapshotWithCursorCheckpoint(
                        checkpointColumns,
                        primaryKeyCheckpoint = upperBound,
                        cursor,
                        stream
                    )
            }
}

/**
 * Default implementation of a [JdbcPartition] for a cursor incremental partition. These are always
 * splittable.
 */
class MySqlSourceJdbcCursorIncrementalPartition(
    selectQueryGenerator: SelectQueryGenerator,
    override val streamState: DefaultJdbcStreamState,
    cursor: Field,
    val cursorLowerBound: JsonNode,
    override val isLowerBoundIncluded: Boolean,
    cursorUpperBound: JsonNode?,
) :
    MySqlSourceJdbcCursorPartition(
        selectQueryGenerator,
        streamState,
        listOf(cursor),
        cursor,
        cursorUpperBound
    ) {

    override val lowerBound: List<JsonNode> = listOf(cursorLowerBound)
    override val upperBound: List<JsonNode>
        get() = listOf(cursorUpperBound)

    override val completeState: OpaqueStateValue
        get() =
            MySqlSourceJdbcStreamStateValue.cursorIncrementalCheckpoint(
                cursor,
                cursorCheckpoint = cursorUpperBound,
                stream,
            )

    override fun incompleteState(lastRecord: ObjectNode): OpaqueStateValue =
        MySqlSourceJdbcStreamStateValue.cursorIncrementalCheckpoint(
            cursor,
            cursorCheckpoint = lastRecord[cursor.id] ?: Jsons.nullNode(),
            stream,
        )
}

@Singleton
@Primary
@Requires(property = MODE_PROPERTY, value = "concurrent")
class MySqlJdbcConcurrentPartitionsCreatorFactory<
    A : JdbcSharedState,
    S : JdbcStreamState<A>,
    P : JdbcPartition<S>,
>(
    partitionFactory: JdbcPartitionFactory<A, S, P>,
) : JdbcPartitionsCreatorFactory<A, S, P>(partitionFactory) {
    override fun partitionsCreator(partition: P): JdbcPartitionsCreator<A, S, P> =
        MySqlJdbcConcurrentPartitionsCreator(partition, partitionFactory)
}

class MySqlJdbcConcurrentPartitionsCreator<
    A : JdbcSharedState, S : JdbcStreamState<A>, P : JdbcPartition<S>>(
    partition: P,
    partitionFactory: JdbcPartitionFactory<A, S, P>,
) : JdbcPartitionsCreator<A, S, P>(partition, partitionFactory) {
    private val log = KotlinLogging.logger {}
    val tableEstimateQuery =
        "SELECT DATA_LENGTH FROM information_schema.TABLES t WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s'"

    override suspend fun run(): List<PartitionReader> {
        // Ensure that the cursor upper bound is known, if required.
        if (partition is JdbcCursorPartition<*>) {
            ensureCursorUpperBound()
            if (
                streamState.cursorUpperBound == null || streamState.cursorUpperBound?.isNull == true
            ) {
                log.info { "Maximum cursor column value query found that the table was empty." }
                return listOf(CheckpointOnlyPartitionReader())
            }
        }
        // Handle edge case where the table can't be sampled.
        if (!sharedState.withSampling) {
            log.warn {
                "Table cannot be read by concurrent partition readers because it cannot be sampled."
            }
            // TODO: adaptive fetchSize computation?
            return listOf(JdbcNonResumablePartitionReader(partition))
        }
        // Sample the table for partition split boundaries and for record byte sizes.
        val sample: Sample<Pair<OpaqueStateValue?, Long>> = collectSample { record: ObjectNode ->
            val boundary: OpaqueStateValue? =
                (partition as? JdbcSplittablePartition<*>)?.incompleteState(record)
            val rowByteSize: Long = sharedState.rowByteSizeEstimator().apply(record)
            boundary to rowByteSize
        }
        if (sample.kind == Sample.Kind.EMPTY) {
            log.info { "Sampling query found that the table was empty." }
            return listOf(CheckpointOnlyPartitionReader())
        }
        val rowByteSizeSample: Sample<Long> = sample.map { (_, rowByteSize: Long) -> rowByteSize }
        streamState.fetchSize = sharedState.jdbcFetchSizeEstimator().apply(rowByteSizeSample)
        if (partition !is JdbcSplittablePartition<*>) {
            log.warn {
                "Table cannot be read by concurrent partition readers because it cannot be split."
            }
            return listOf(JdbcNonResumablePartitionReader(partition))
        }
        val tableByteSizeEstimate: Long =
            findTableSizeEstimate(stream, partitionFactory.sharedState)

        if (tableByteSizeEstimate == 0L) {
            log.info { "Unable to get table estimate size" }
            return listOf(JdbcNonResumablePartitionReader(partition))
        }

        log.info { "Table memory size estimated at ${tableByteSizeEstimate shr 20} MiB." }
        log.info { "Target partition size is ${sharedState.targetPartitionByteSize shr 20} MiB." }
        val secondarySamplingRate: Double =
            if (tableByteSizeEstimate <= sharedState.targetPartitionByteSize) {
                return listOf(JdbcNonResumablePartitionReader(partition))
            } else {
                val expectedPartitionByteSize: Long =
                    tableByteSizeEstimate / sharedState.maxSampleSize
                if (expectedPartitionByteSize < sharedState.targetPartitionByteSize) {
                    expectedPartitionByteSize.toDouble() / sharedState.targetPartitionByteSize
                } else {
                    1.0
                }
            }
        val random = Random(tableByteSizeEstimate) // RNG output is repeatable.
        val splitBoundaries: List<OpaqueStateValue> =
            sample.sampledValues
                .filter { random.nextDouble() < secondarySamplingRate }
                .mapNotNull { (splitBoundary: OpaqueStateValue?, _) -> splitBoundary }
                .distinct()
        val partitions: List<JdbcPartition<*>> = partitionFactory.split(partition, splitBoundaries)
        log.info { "Table will be read by ${partitions.size} concurrent partition reader(s)." }
        return partitions.map { JdbcNonResumablePartitionReader(it) }
    }

    private fun findTableSizeEstimate(stream: Stream, sharedState: JdbcSharedState): Long {
        val jdbcConnectionFactory = JdbcConnectionFactory(sharedState.configuration)
        jdbcConnectionFactory.get().use { connection ->
            val stmt =
                connection.prepareStatement(
                    tableEstimateQuery.format(stream.namespace, stream.name)
                )
            val rs = stmt.executeQuery()
            if (rs.next()) {
                val tableSize: Long = rs.getLong(1)
                return tableSize
            }
        }
        return 0
    }
}
