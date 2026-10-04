package app.meanwhile.domain.cgm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Parser for xDrip+'s local web service `sgv.json` (Nightscout entries format, newest first):
 * `[{"date": 1759540000000, "sgv": 123, "delta": -1.5, "direction": "Flat", "units_hint": "mgdl", …}]`.
 * `delta` is mg/dL per 5 minutes. `sensor_status` appears on the first entry when `?sensor` is requested.
 * Malformed rows are skipped, never fatal.
 */
object XdripSgv {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, source: String = "xdrip_web"): List<CgmReading> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val date = o["date"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val sgv = o["sgv"]?.jsonPrimitive?.intOrNull ?: o["sgv"]?.jsonPrimitive?.doubleOrNull?.toInt()
                ?: return@mapNotNull null
            if (sgv < 20 || sgv > 600) return@mapNotNull null // xDrip uses tiny values for sensor error codes
            val delta = o["delta"]?.jsonPrimitive?.doubleOrNull
            val direction = o["direction"]?.jsonPrimitive?.contentOrNull
            CgmReading(
                timestamp = Instant.ofEpochMilli(date),
                mgDl = sgv,
                trendRate = delta?.div(5.0) ?: Trend.rateForDirection(direction),
                direction = direction,
                source = source,
                sensorStatus = o["sensor_status"]?.jsonPrimitive?.contentOrNull,
            )
        }.sortedBy { it.timestamp }
    }

    /** True when xDrip reports mmol/L display units (values in sgv are still mg/dL). */
    fun unitsHint(body: String): String? = runCatching {
        ((json.parseToJsonElement(body) as JsonArray).firstOrNull() as? JsonObject)
            ?.get("units_hint")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
