package app.meanwhile.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.meanwhile.data.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportResult(val zip: File, val csvByTable: Map<String, File>, val rowsByTable: Map<String, Int>)

/** Settings → Export: one CSV per table plus a zip of all of them (spec §12.4). */
class CsvExporter(private val context: Context, private val sync: SyncEngine) {

    suspend fun export(from: Long, to: Long, tables: Set<String>? = null): ExportResult = withContext(Dispatchers.IO) {
        val data = sync.export(from, to, tables)
        val dir = File(context.cacheDir, EXPORT_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val stamp = "${day(from)}_to_${day(to)}"
        val csvs = data.mapValues { (name, value) ->
            File(dir, "$name-$stamp.csv").also { writeCsv(it, value.first, value.second) }
        }
        val zip = File(dir, "meanwhile-export-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            for (file in csvs.values) {
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        ExportResult(zip, csvs, data.mapValues { it.value.second.size })
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "zip") "application/zip" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${file.name}")
    }

    private fun writeCsv(file: File, columns: List<String>, rows: List<JsonObject>) {
        file.bufferedWriter().use { w ->
            w.write(columns.joinToString(",") { escape(it) })
            w.write("\r\n")
            for (row in rows) {
                w.write(columns.joinToString(",") { escape(cell(row[it])) })
                w.write("\r\n")
            }
        }
    }

    private fun cell(value: kotlinx.serialization.json.JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun escape(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    private fun day(epochMillis: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    private companion object {
        const val EXPORT_DIR = "exports"
    }
}
