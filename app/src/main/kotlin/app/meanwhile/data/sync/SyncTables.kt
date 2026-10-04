@file:OptIn(ExperimentalSerializationApi::class)

package app.meanwhile.data.sync

import app.meanwhile.data.db.AiCallEntity
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.CgmReadingEntity
import app.meanwhile.data.db.DoseEntity
import app.meanwhile.data.db.FactorDefinitionEntity
import app.meanwhile.data.db.FactorEventEntity
import app.meanwhile.data.db.FeedbackEntity
import app.meanwhile.data.db.InputEntity
import app.meanwhile.data.db.MealEntity
import app.meanwhile.data.db.OutcomeEntity
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.db.ProposalEntity
import app.meanwhile.data.db.Record
import app.meanwhile.data.db.SyncState
import app.meanwhile.data.json.RecordJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One synced table: how to read pending rows, mark them synced, store pulled rows and export. */
class SyncTable<T : Record>(
    val name: String,
    private val serializer: KSerializer<T>,
    private val pendingRows: suspend (Int) -> List<T>,
    private val markRowsSynced: suspend (List<String>) -> Unit,
    private val markRowsRejected: suspend (List<String>) -> Unit,
    private val insertRows: suspend (List<T>) -> Unit,
    private val rowsBetween: suspend (Long, Long) -> List<T>,
    private val asSynced: (T) -> T,
) {
    class Batch(val ids: List<String>, val rows: List<JsonObject>)

    suspend fun pending(limit: Int): Batch {
        val items = pendingRows(limit)
        return Batch(items.map { it.id }, items.map { encode(it) })
    }

    suspend fun markSynced(ids: List<String>) = markRowsSynced(ids)

    suspend fun markRejected(ids: List<String>) = markRowsRejected(ids)

    suspend fun insertPulled(rows: List<JsonElement>) {
        insertRows(rows.map { asSynced(RecordJson.decodeFromJsonElement(serializer, it)) })
    }

    suspend fun export(from: Long, to: Long): List<JsonObject> = rowsBetween(from, to).map { encode(it) }

    /** Column names in serialization order (used for CSV headers even when a table is empty). */
    val columns: List<String> =
        (0 until serializer.descriptor.elementsCount).map { i ->
            RecordJson.configuration.namingStrategy?.serialNameForJson(serializer.descriptor, i, serializer.descriptor.getElementName(i))
                ?: serializer.descriptor.getElementName(i)
        }

    private fun encode(item: T): JsonObject = RecordJson.encodeToJsonElement(serializer, item).jsonObject
}

fun syncTables(db: AppDatabase): List<SyncTable<*>> = listOf(
    db.cgm().let { d ->
        SyncTable(
            "cgm_readings", CgmReadingEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.meals().let { d ->
        SyncTable(
            "meals", MealEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.factorEvents().let { d ->
        SyncTable(
            "factor_events", FactorEventEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.doses().let { d ->
        SyncTable(
            "doses", DoseEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.proposals().let { d ->
        SyncTable(
            "proposals", ProposalEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.outcomes().let { d ->
        SyncTable(
            "outcomes", OutcomeEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.profileVersions().let { d ->
        SyncTable(
            "profile_versions", ProfileVersionEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.factorDefinitions().let { d ->
        SyncTable(
            "factor_definitions", FactorDefinitionEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.aiCalls().let { d ->
        SyncTable(
            "ai_calls", AiCallEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.feedback().let { d ->
        SyncTable(
            "feedback", FeedbackEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
    db.inputs().let { d ->
        SyncTable(
            "inputs", InputEntity.serializer(),
            d::pending, d::markSynced, d::markFailed, { d.insertAll(it) }, d::between,
        ) { it.copy(syncState = SyncState.SYNCED) }
    },
)
