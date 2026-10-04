package app.meanwhile.data.profile

import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.FactorDefinitionEntity
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.json.AppJson
import app.meanwhile.domain.factors.FactorEngine
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.ProfileDiff
import app.meanwhile.domain.profile.ProfileJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.ZoneId

/** The profile Next Best Action uses, and where it came from (spec §8, §9.5 profile callout). */
data class ProfileState(
    val profile: Profile,
    /** null = built-in starting values (no version saved yet). */
    val version: ProfileVersionEntity?,
) {
    val versionLabel: String get() = version?.let { "v${it.version}" } ?: "v0 (starting values)"
}

object ProfileStatus {
    const val PENDING = "pending"
    const val ACCEPTED = "accepted"
    const val EDITED = "edited"
    const val REJECTED = "rejected"
    val APPLIED = setOf(ACCEPTED, EDITED)
}

object ProfileSource {
    const val LEARN_CYCLE = "learn_cycle"
    const val AI_UPDATE = "ai_update"
    const val OFFLINE_FALLBACK = "offline_fallback"
    const val MANUAL = "manual"
    const val AUTO_F11 = "auto_f11"
    const val SLEEP_CHECKIN = "sleep_checkin"
}

val changeListSerializer = ListSerializer(ProfileChange.serializer())

fun ProfileVersionEntity.decodedProfile(): Profile = ProfileJson.decode(profile)
fun ProfileVersionEntity.changes(): List<ProfileChange> =
    runCatching { AppJson.decodeFromString(changeListSerializer, diff) }.getOrDefault(emptyList())

class ProfileRepository(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val onWrite: () -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val writeLock = Mutex()

    val versions: Flow<List<ProfileVersionEntity>> = db.profileVersions().allFlow()

    val current: Flow<ProfileState> = versions.map { pickCurrent(it) }

    suspend fun current(): ProfileState = pickCurrent(db.profileVersions().all())

    /** Latest accepted/edited version wins (spec §8). */
    private fun pickCurrent(all: List<ProfileVersionEntity>): ProfileState {
        val v = all.filter { it.status in ProfileStatus.APPLIED }
            .maxWithOrNull(compareBy<ProfileVersionEntity> { it.version }.thenBy { it.createdAt })
        return ProfileState(v?.let { runCatching { it.decodedProfile() }.getOrNull() } ?: Profile(), v)
    }

    /** Pending proposals (learn cycle, AI refinements) that haven't been decided yet. */
    fun pendingFlow(): Flow<List<ProfileVersionEntity>> = versions.map { all ->
        val decided = all.mapNotNull { it.supersedesId }.toSet()
        all.filter { it.status == ProfileStatus.PENDING && it.id !in decided }
    }

    /**
     * Saves [newProfile] as a new version. Expired factor activations are pruned first. The diff is
     * computed against the current applied profile unless [changes] are given.
     */
    suspend fun saveVersion(
        newProfile: Profile,
        source: String,
        status: String,
        summary: String,
        changes: List<ProfileChange>? = null,
        supersedesId: String? = null,
        aiCallId: String? = null,
        now: Instant = Instant.now(),
    ): ProfileVersionEntity = writeLock.withLock {
        val base = current()
        val pruned = if (status == ProfileStatus.PENDING) newProfile else FactorEngine.prune(newProfile, now, zone())
        val diff = changes ?: ProfileDiff.diff(base.profile, pruned)
        val m = records.meta(now = now.toEpochMilli())
        val entity = ProfileVersionEntity(
            id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
            supersedesId = supersedesId,
            version = db.profileVersions().maxVersion() + 1,
            source = source,
            status = status,
            profile = ProfileJson.encode(pruned),
            diff = AppJson.encodeToString(changeListSerializer, diff),
            decidedAt = if (status == ProfileStatus.PENDING) null else now.toEpochMilli(),
            baseVersionId = base.version?.id,
            aiCallId = aiCallId,
            summary = summary,
        )
        db.profileVersions().insert(entity)
        if (status in ProfileStatus.APPLIED) recordDefinitionChanges(base.profile, pruned, entity.id, now)
        onWrite()
        entity
    }

    /** Snapshot of each factor definition that is new or changed, for the factor_definitions table. */
    private suspend fun recordDefinitionChanges(old: Profile, new: Profile, versionId: String, now: Instant) {
        val oldById = old.factors.associateBy { it.id }
        val changed = new.factors.filter { oldById[it.id] != it }
        if (changed.isEmpty()) return
        val rows = changed.map { def ->
            val m = records.meta(now = now.toEpochMilli())
            FactorDefinitionEntity(
                id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
                factorId = def.id,
                definition = ProfileJson.json.encodeToString(app.meanwhile.domain.profile.FactorDefinition.serializer(), def),
                profileVersionId = versionId,
            )
        }
        db.factorDefinitions().insertAll(rows)
    }

    suspend fun latestVersion(): ProfileVersionEntity? = db.profileVersions().all().firstOrNull()

    suspend fun byId(id: String): ProfileVersionEntity? = db.profileVersions().byId(id)

    suspend fun appliedVersions(): List<ProfileVersionEntity> = versions.first().filter { it.status in ProfileStatus.APPLIED }
}
