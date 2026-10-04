package app.meanwhile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Inserts ignore duplicates: ids are UUIDs, so a conflict is always the same record arriving twice. */
interface RecordDao<T> {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: T): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<T>): List<Long>
}

@Dao
interface CgmDao : RecordDao<CgmReadingEntity> {
    @Query("SELECT * FROM cgm_readings WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<CgmReadingEntity>

    @Query("UPDATE cgm_readings SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE cgm_readings SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM cgm_readings WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<CgmReadingEntity>

    @Query("SELECT * FROM cgm_readings ORDER BY recordedAt DESC LIMIT 1")
    fun latestFlow(): Flow<CgmReadingEntity?>

    @Query("SELECT * FROM cgm_readings ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latest(): CgmReadingEntity?

    @Query("SELECT * FROM cgm_readings WHERE recordedAt >= :from ORDER BY recordedAt")
    fun sinceFlow(from: Long): Flow<List<CgmReadingEntity>>

    @Query("SELECT * FROM cgm_readings WHERE recordedAt >= :from ORDER BY recordedAt")
    suspend fun since(from: Long): List<CgmReadingEntity>
}

@Dao
interface MealDao : RecordDao<MealEntity> {
    @Query("SELECT * FROM meals WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<MealEntity>

    @Query("UPDATE meals SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE meals SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM meals WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<MealEntity>

    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun byId(id: String): MealEntity?
}

@Dao
interface FactorEventDao : RecordDao<FactorEventEntity> {
    @Query("SELECT * FROM factor_events WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<FactorEventEntity>

    @Query("UPDATE factor_events SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE factor_events SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM factor_events WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<FactorEventEntity>

    @Query("SELECT * FROM factor_events WHERE recordedAt >= :from ORDER BY recordedAt")
    fun sinceFlow(from: Long): Flow<List<FactorEventEntity>>

    @Query("SELECT * FROM factor_events WHERE recordedAt >= :from ORDER BY recordedAt")
    suspend fun since(from: Long): List<FactorEventEntity>
}

@Dao
interface DoseDao : RecordDao<DoseEntity> {
    @Query("SELECT * FROM doses WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<DoseEntity>

    @Query("UPDATE doses SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE doses SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM doses WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<DoseEntity>

    @Query("SELECT * FROM doses WHERE givenAt >= :from ORDER BY givenAt")
    fun sinceFlow(from: Long): Flow<List<DoseEntity>>

    @Query("SELECT * FROM doses WHERE givenAt >= :from ORDER BY givenAt")
    suspend fun since(from: Long): List<DoseEntity>

    @Query("SELECT * FROM doses WHERE proposalId = :proposalId ORDER BY givenAt")
    suspend fun forProposal(proposalId: String): List<DoseEntity>

    @Query("SELECT * FROM doses WHERE insulin = 'rapid' ORDER BY givenAt DESC LIMIT 1")
    suspend fun latestRapid(): DoseEntity?
}

@Dao
interface ProposalDao : RecordDao<ProposalEntity> {
    @Query("SELECT * FROM proposals WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<ProposalEntity>

    @Query("UPDATE proposals SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE proposals SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM proposals WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<ProposalEntity>

    @Query("SELECT * FROM proposals WHERE id = :id")
    suspend fun byId(id: String): ProposalEntity?

    @Query("SELECT * FROM proposals WHERE recordedAt >= :from ORDER BY recordedAt")
    suspend fun since(from: Long): List<ProposalEntity>
}

@Dao
interface OutcomeDao : RecordDao<OutcomeEntity> {
    @Query("SELECT * FROM outcomes WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<OutcomeEntity>

    @Query("UPDATE outcomes SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE outcomes SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM outcomes WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<OutcomeEntity>

    @Query("SELECT doseId FROM outcomes")
    suspend fun taggedDoseIds(): List<String>

    @Query("SELECT * FROM outcomes WHERE recordedAt >= :from ORDER BY recordedAt")
    suspend fun since(from: Long): List<OutcomeEntity>
}

@Dao
interface ProfileVersionDao : RecordDao<ProfileVersionEntity> {
    @Query("SELECT * FROM profile_versions WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<ProfileVersionEntity>

    @Query("UPDATE profile_versions SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE profile_versions SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM profile_versions WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<ProfileVersionEntity>

    @Query("SELECT * FROM profile_versions ORDER BY version DESC, createdAt DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<ProfileVersionEntity>>

    @Query("SELECT * FROM profile_versions ORDER BY version DESC, createdAt DESC")
    suspend fun all(): List<ProfileVersionEntity>

    // The dose path reads the current profile constantly; these stay O(1) as versions accumulate
    // (a year of learn cycles is thousands of rows, each holding a full profile JSON).
    @Query("SELECT * FROM profile_versions WHERE status IN ('accepted', 'edited') ORDER BY version DESC, createdAt DESC LIMIT 1")
    fun currentAppliedFlow(): Flow<ProfileVersionEntity?>

    @Query("SELECT * FROM profile_versions WHERE status IN ('accepted', 'edited') ORDER BY version DESC, createdAt DESC LIMIT 1")
    suspend fun currentApplied(): ProfileVersionEntity?

    @Query(
        "SELECT * FROM profile_versions WHERE status = 'pending' AND id NOT IN " +
            "(SELECT supersedesId FROM profile_versions WHERE supersedesId IS NOT NULL) ORDER BY version DESC",
    )
    fun pendingUndecidedFlow(): Flow<List<ProfileVersionEntity>>

    @Query("SELECT * FROM profile_versions WHERE supersedesId = :id LIMIT 1")
    suspend fun supersededBy(id: String): ProfileVersionEntity?

    @Query(
        "SELECT * FROM profile_versions WHERE status = 'pending' AND id NOT IN " +
            "(SELECT supersedesId FROM profile_versions WHERE supersedesId IS NOT NULL)",
    )
    suspend fun pendingUndecided(): List<ProfileVersionEntity>

    @Query("SELECT * FROM profile_versions WHERE aiCallId IS NOT NULL AND createdAt >= :from")
    suspend fun aiLinkedSince(from: Long): List<ProfileVersionEntity>

    @Query("SELECT COALESCE(MAX(version), 0) FROM profile_versions")
    suspend fun maxVersion(): Int

    @Query("SELECT * FROM profile_versions WHERE id = :id")
    suspend fun byId(id: String): ProfileVersionEntity?
}

@Dao
interface FactorDefinitionDao : RecordDao<FactorDefinitionEntity> {
    @Query("SELECT * FROM factor_definitions WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<FactorDefinitionEntity>

    @Query("UPDATE factor_definitions SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE factor_definitions SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM factor_definitions WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<FactorDefinitionEntity>
}

@Dao
interface AiCallDao : RecordDao<AiCallEntity> {
    @Query("SELECT * FROM ai_calls WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<AiCallEntity>

    @Query("UPDATE ai_calls SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE ai_calls SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM ai_calls WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<AiCallEntity>

    @Query("SELECT * FROM ai_calls WHERE recordedAt >= :from ORDER BY recordedAt")
    suspend fun since(from: Long): List<AiCallEntity>

    @Query("SELECT * FROM ai_calls ORDER BY recordedAt DESC LIMIT 1")
    fun latestFlow(): Flow<AiCallEntity?>
}

@Dao
interface FeedbackDao : RecordDao<FeedbackEntity> {
    @Query("SELECT * FROM feedback WHERE context = :context ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun byContext(context: String, limit: Int): List<FeedbackEntity>

    @Query("SELECT * FROM feedback WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<FeedbackEntity>

    @Query("UPDATE feedback SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE feedback SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM feedback WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<FeedbackEntity>
}

@Dao
interface InputDao : RecordDao<InputEntity> {
    @Query("SELECT * FROM inputs WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<InputEntity>

    @Query("UPDATE inputs SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE inputs SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM inputs WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<InputEntity>

    @Query("SELECT * FROM inputs WHERE recordedAt >= :from ORDER BY recordedAt")
    suspend fun since(from: Long): List<InputEntity>
}

@Dao
interface AiQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AiQueueEntity)

    @Query("SELECT * FROM ai_queue ORDER BY createdAt")
    suspend fun all(): List<AiQueueEntity>

    @Query("SELECT COUNT(*) FROM ai_queue")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM ai_queue WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface LearningLogDao : RecordDao<LearningLogEntity> {
    @Query("SELECT * FROM learning_log WHERE syncState = 0 ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int): List<LearningLogEntity>

    @Query("UPDATE learning_log SET syncState = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE learning_log SET syncState = 2 WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("SELECT * FROM learning_log WHERE recordedAt BETWEEN :from AND :to ORDER BY recordedAt")
    suspend fun between(from: Long, to: Long): List<LearningLogEntity>

    @Query("SELECT * FROM learning_log ORDER BY recordedAt DESC, createdAt DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<LearningLogEntity>>

    @Query("SELECT * FROM learning_log WHERE recordedAt >= :from ORDER BY recordedAt")
    suspend fun since(from: Long): List<LearningLogEntity>

    @Query("SELECT * FROM learning_log WHERE id = :id")
    suspend fun byId(id: String): LearningLogEntity?

    /** Applied changes not yet closed by a kept / reverted / undone row. */
    @Query(
        "SELECT * FROM learning_log WHERE kind = 'applied' AND id NOT IN " +
            "(SELECT supersedesId FROM learning_log WHERE supersedesId IS NOT NULL) ORDER BY recordedAt",
    )
    suspend fun openChanges(): List<LearningLogEntity>

    @Query(
        "SELECT * FROM learning_log WHERE kind = 'applied' AND id NOT IN " +
            "(SELECT supersedesId FROM learning_log WHERE supersedesId IS NOT NULL) ORDER BY recordedAt DESC",
    )
    fun openChangesFlow(): Flow<List<LearningLogEntity>>

    @Query("SELECT * FROM learning_log WHERE kind = :kind ORDER BY recordedAt DESC LIMIT 1")
    suspend fun latest(kind: String): LearningLogEntity?
}

@Dao
interface SyncDao {
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM cgm_readings WHERE syncState = 0)
             + (SELECT COUNT(*) FROM meals WHERE syncState = 0)
             + (SELECT COUNT(*) FROM factor_events WHERE syncState = 0)
             + (SELECT COUNT(*) FROM doses WHERE syncState = 0)
             + (SELECT COUNT(*) FROM proposals WHERE syncState = 0)
             + (SELECT COUNT(*) FROM outcomes WHERE syncState = 0)
             + (SELECT COUNT(*) FROM profile_versions WHERE syncState = 0)
             + (SELECT COUNT(*) FROM factor_definitions WHERE syncState = 0)
             + (SELECT COUNT(*) FROM ai_calls WHERE syncState = 0)
             + (SELECT COUNT(*) FROM feedback WHERE syncState = 0)
             + (SELECT COUNT(*) FROM inputs WHERE syncState = 0)
             + (SELECT COUNT(*) FROM learning_log WHERE syncState = 0)
        """,
    )
    fun pendingCount(): Flow<Int>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM cgm_readings WHERE syncState = 2)
             + (SELECT COUNT(*) FROM meals WHERE syncState = 2)
             + (SELECT COUNT(*) FROM factor_events WHERE syncState = 2)
             + (SELECT COUNT(*) FROM doses WHERE syncState = 2)
             + (SELECT COUNT(*) FROM proposals WHERE syncState = 2)
             + (SELECT COUNT(*) FROM outcomes WHERE syncState = 2)
             + (SELECT COUNT(*) FROM profile_versions WHERE syncState = 2)
             + (SELECT COUNT(*) FROM factor_definitions WHERE syncState = 2)
             + (SELECT COUNT(*) FROM ai_calls WHERE syncState = 2)
             + (SELECT COUNT(*) FROM feedback WHERE syncState = 2)
             + (SELECT COUNT(*) FROM inputs WHERE syncState = 2)
             + (SELECT COUNT(*) FROM learning_log WHERE syncState = 2)
        """,
    )
    fun rejectedCount(): Flow<Int>
}
