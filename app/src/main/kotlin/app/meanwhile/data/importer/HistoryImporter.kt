package app.meanwhile.data.importer

import android.util.JsonReader
import android.util.JsonToken
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.cgm.CgmRepository
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.DoseEntity
import app.meanwhile.data.input.ConversationLog
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.ProfilePatch
import app.meanwhile.domain.util.UuidV7
import app.meanwhile.log.AppLog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant

/** Settings from the old app's export, as numbers this app understands (null = not in the file). */
data class OldSettings(
    val icr: Double? = null,
    val isf: Double? = null,
    val target: Double? = null,
    val durationMin: Double? = null,
    val peakMin: Double? = null,
    val delayMin: Double? = null,
) {
    /** The profile changes that would apply them (only values that differ). */
    fun changes(current: Profile): List<ProfileChange> = listOfNotNull(
        icr?.let { "dose.icr" to it }, isf?.let { "dose.isf" to it }, target?.let { "dose.target" to it },
        durationMin?.let { "iob.durationMin" to it }, peakMin?.let { "iob.peakMin" to it }, delayMin?.let { "iob.delayMin" to it },
    ).mapNotNull { (path, v) ->
        val old = ProfilePatch.get(current, path) ?: return@mapNotNull null
        if ((old as? JsonPrimitive)?.content?.toDoubleOrNull() == v) null else ProfileChange(path, old, JsonPrimitive(v), reason = "from the old app's settings")
    }

    val isEmpty: Boolean get() = listOf(icr, isf, target, durationMin, peakMin, delayMin).all { it == null }
}

data class ImportResult(
    val readingsRead: Int,
    val readingsAdded: Int,
    val dosesRead: Int,
    val dosesAdded: Int,
    /** Doses at or after the first dose logged in this app: already here, so not imported twice. */
    val dosesSkippedOverlap: Int,
    val firstAt: Long?,
    val lastAt: Long?,
    val settings: OldSettings,
) {
    val summary: String
        get() = "Imported $readingsAdded of $readingsRead CGM readings and $dosesAdded of $dosesRead doses" +
            (if (dosesSkippedOverlap > 0) " ($dosesSkippedOverlap from after you started logging here were skipped)" else "") + "."
}

/**
 * Imports the old Meanwhile app's JSON export (2.0): `bg` → CGM readings, `insulin` → doses (bolus =
 * rapid, basal = long), `profile` → [OldSettings] offered for Danny to apply. Streams the file (it is
 * tens of MB) and skips each reading's `raw` copy. Append-only and repeatable: ids are derived from
 * the data, so importing the same file again adds nothing. Doses from when this app was already in use
 * are skipped, so insulin on board never counts one injection twice.
 */
class HistoryImporter(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val cgm: CgmRepository,
    private val conversation: ConversationLog,
    private val onWrite: () -> Unit,
) {
    private data class OldDose(val at: Long, val units: Double, val kind: String, val source: String?, val note: String?)

    suspend fun import(input: InputStream, onProgress: (String) -> Unit = {}): ImportResult {
        val readings = ArrayList<CgmReading>(4096)
        val doses = ArrayList<OldDose>()
        var settings = OldSettings()
        var readingsRead = 0
        var readingsAdded = 0
        var first: Long? = null
        var last: Long? = null

        suspend fun flush() {
            if (readings.isEmpty()) return
            readingsAdded += cgm.save(readings)
            readings.clear()
            onProgress("$readingsRead CGM readings read…")
        }

        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "bg" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            reading(r)?.let { rd ->
                                readingsRead++
                                val t = rd.timestamp.toEpochMilli()
                                first = minOf(first ?: t, t)
                                last = maxOf(last ?: t, t)
                                readings += rd
                                if (readings.size >= BATCH) flush()
                            }
                        }
                        r.endArray()
                        flush()
                    }
                    "insulin" -> {
                        r.beginArray()
                        while (r.hasNext()) dose(r)?.let { doses += it }
                        r.endArray()
                    }
                    "profile" -> settings = profile(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        // Doses: only from before this app's own first dose (those after are already logged here).
        val cutoff = db.doses().earliestGivenAt()
        val keep = doses.filter { cutoff == null || it.at < cutoff }
        val m = records.meta()
        val rows = keep.map { d ->
            val insulin = if (d.kind == "basal") "long" else "rapid"
            DoseEntity(
                id = UuidV7.deterministic(d.at, "import-dose:${d.at}:${d.units}:$insulin"),
                userId = m.userId, createdAt = m.createdAt, recordedAt = d.at,
                insulin = insulin, units = d.units, givenAt = d.at,
                details = buildJsonObject {
                    put("source", "import")
                    d.source?.let { put("old_source", it) }
                    d.note?.let { put("note", it) }
                }.toString(),
            )
        }
        var dosesAdded = 0
        rows.chunked(BATCH).forEach { chunk -> dosesAdded += db.doses().insertAll(chunk).count { it != -1L } }
        if (dosesAdded > 0) onWrite()

        val result = ImportResult(readingsRead, readingsAdded, doses.size, dosesAdded, doses.size - keep.size, first, last, settings)
        AppLog.i("Import", result.summary)
        conversation.action(
            null, "Imported history → ${result.summary}",
            buildJsonObject {
                put("source", "import")
                first?.let { put("from", Instant.ofEpochMilli(it).toString()) }
                last?.let { put("to", Instant.ofEpochMilli(it).toString()) }
            },
        )
        return result
    }

    private fun reading(r: JsonReader): CgmReading? {
        var ts: Long? = null
        var mg: Int? = null
        var trend: String? = null
        var source: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "ts" -> ts = longOrNull(r)
                "mgdl" -> mg = longOrNull(r)?.toInt()
                "trend" -> trend = stringOrNull(r)
                "source" -> source = stringOrNull(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        val t = ts ?: return null
        val v = mg?.takeIf { it in 20..600 } ?: return null
        return CgmReading(Instant.ofEpochMilli(t), v, null, trend, "import:" + (source ?: "old-app"))
    }

    private fun dose(r: JsonReader): OldDose? {
        var ts: Long? = null
        var units: Double? = null
        var kind: String? = null
        var source: String? = null
        var note: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "ts" -> ts = longOrNull(r)
                "units" -> units = doubleOrNull(r)
                "kind" -> kind = stringOrNull(r)
                "source" -> source = stringOrNull(r)
                "note" -> note = stringOrNull(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        val t = ts ?: return null
        val u = units?.takeIf { it > 0 && it.isFinite() } ?: return null
        return OldDose(t, u, kind ?: "bolus", source, note)
    }

    /** `profile` is a list in the export (one "current" row); the last row wins. */
    private fun profile(r: JsonReader): OldSettings {
        var out = OldSettings()
        fun one() {
            val v = mutableMapOf<String, Double>()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                if (r.peek() == JsonToken.NUMBER) v[name] = r.nextDouble() else r.skipValue()
            }
            r.endObject()
            out = OldSettings(
                icr = v["ic_ratio"], isf = v["isf"], target = v["target_bg"],
                durationMin = v["dia_hours"]?.let { it * 60 }, peakMin = v["peak_min"], delayMin = v["delay_min"],
            )
        }
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) if (r.peek() == JsonToken.BEGIN_OBJECT) one() else r.skipValue()
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> one()
            else -> r.skipValue()
        }
        return out
    }

    private fun longOrNull(r: JsonReader): Long? = when (r.peek()) {
        JsonToken.NUMBER -> r.nextString().toDoubleOrNull()?.toLong()
        JsonToken.STRING -> r.nextString().toDoubleOrNull()?.toLong()
        else -> { r.skipValue(); null }
    }

    private fun doubleOrNull(r: JsonReader): Double? = when (r.peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> r.nextString().toDoubleOrNull()
        else -> { r.skipValue(); null }
    }

    private fun stringOrNull(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString()
        else -> { r.skipValue(); null }
    }

    private companion object {
        const val BATCH = 2000
    }
}
