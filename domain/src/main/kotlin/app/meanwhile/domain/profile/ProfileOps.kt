package app.meanwhile.domain.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProfileJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(p: Profile): String = json.encodeToString(Profile.serializer(), p)
    fun decode(text: String): Profile = json.decodeFromString(Profile.serializer(), text)
    fun tree(p: Profile): JsonObject = json.encodeToJsonElement(Profile.serializer(), p).jsonObject
    fun fromTree(tree: JsonElement): Profile = json.decodeFromJsonElement(Profile.serializer(), tree)
}

/**
 * One change to a profile, addressed by a dotted path. List items with an `id` (factors) or
 * `factorId` (active) are addressed by that id: `dose.icr`, `factors.F7.maxWeight`,
 * `factors.F11.params.perHour`, `factors.F13` (add a whole new factor), `active.F8`.
 */
@Serializable
data class ProfileChange(
    val path: String,
    val old: JsonElement? = null,
    val new: JsonElement? = null,
    val reason: String? = null,
    /** accepted | edited | rejected (filled in when Danny decides). */
    val decision: String? = null,
)

object ProfilePatch {

    fun get(profile: Profile, path: String): JsonElement? {
        var node: JsonElement? = ProfileJson.tree(profile)
        for (seg in path.split('.')) {
            node = when (val n = node) {
                is JsonObject -> n[seg]
                is JsonArray -> n.firstOrNull { keyOf(it) == seg }
                else -> null
            }
        }
        return node
    }

    /** Applies all [changes] in order; fails (nothing applied) if any path is invalid or the result doesn't decode. */
    fun apply(profile: Profile, changes: List<ProfileChange>): Result<Profile> = runCatching {
        var tree: JsonElement = ProfileJson.tree(profile)
        for (c in changes) tree = set(tree, c.path.split('.'), c.new ?: JsonNull)
        ProfileJson.fromTree(tree)
    }

    private fun set(node: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        val seg = path.first()
        val rest = path.drop(1)
        return when (node) {
            is JsonObject -> {
                val child = node[seg]
                val newChild = if (rest.isEmpty()) value else set(child ?: JsonObject(emptyMap()), rest, value)
                val map = node.toMutableMap()
                if (newChild is JsonNull && rest.isEmpty()) map.remove(seg) else map[seg] = newChild
                JsonObject(map)
            }
            is JsonArray -> {
                val idx = node.indexOfFirst { keyOf(it) == seg }
                val items = node.toMutableList()
                if (rest.isEmpty()) {
                    when {
                        value is JsonNull && idx >= 0 -> items.removeAt(idx)
                        value is JsonNull -> Unit
                        idx >= 0 -> items[idx] = value
                        else -> items.add(value)
                    }
                } else {
                    require(idx >= 0) { "No item '$seg' in list" }
                    items[idx] = set(items[idx], rest, value)
                }
                JsonArray(items)
            }
            else -> throw IllegalArgumentException("Path segment '$seg' goes through a value")
        }
    }

    internal fun keyOf(item: JsonElement): String? {
        val o = item as? JsonObject ?: return null
        return (o["id"] ?: o["factorId"])?.jsonPrimitive?.contentOrNull
    }
}

object ProfileDiff {
    /** Leaf-level differences from [a] to [b] (lists keyed by id / factorId). */
    fun diff(a: Profile, b: Profile): List<ProfileChange> {
        val out = mutableListOf<ProfileChange>()
        walk("", ProfileJson.tree(a), ProfileJson.tree(b), out)
        return out
    }

    private fun walk(path: String, x: JsonElement?, y: JsonElement?, out: MutableList<ProfileChange>) {
        if (x == y) return
        fun child(seg: String) = if (path.isEmpty()) seg else "$path.$seg"
        when {
            x is JsonObject && y is JsonObject -> (x.keys + y.keys).forEach { walk(child(it), x[it], y[it], out) }
            x is JsonArray && y is JsonArray && keyed(x) && keyed(y) -> {
                val xm = x.groupBy { ProfilePatch.keyOf(it)!! }
                val ym = y.groupBy { ProfilePatch.keyOf(it)!! }
                for (k in (xm.keys + ym.keys)) {
                    val xi = xm[k]
                    val yi = ym[k]
                    when {
                        xi?.size == 1 && yi?.size == 1 -> walk(child(k), xi[0], yi[0], out)
                        else -> out += ProfileChange(child(k), xi?.let(::single), yi?.let(::single))
                    }
                }
            }
            else -> out += ProfileChange(path, x, y)
        }
    }

    private fun single(items: List<JsonElement>): JsonElement = if (items.size == 1) items[0] else JsonArray(items)
    private fun keyed(arr: JsonArray) = arr.all { ProfilePatch.keyOf(it) != null }
}

/** Human-readable value for the profile screen and proposals. */
fun JsonElement?.display(): String = when (this) {
    null, is JsonNull -> "—"
    is JsonPrimitive -> contentOrNull ?: toString()
    else -> toString()
}
