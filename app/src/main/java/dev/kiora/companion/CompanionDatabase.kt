package dev.kiora.companion

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "conversation_messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId", "id"])],
)
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val previewImages: String = "[]",
)

@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val leafId: String?,
)

@Entity(
    tableName = "agent_entries",
    foreignKeys = [ForeignKey(
        entity = AgentSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId", "position"])],
)
data class AgentEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val position: Long,
    val parentId: String?,
    val timestamp: Long,
    val messageJson: String,
)

@Entity(tableName = "agent_usages", indices = [Index(value = ["sessionId"])])
data class AgentUsageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val assistantEntryId: String,
    val timestamp: Long,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val cachedInputTokens: Int?,
)

@Entity(tableName = "agent_model_invocations", indices = [Index(value = ["sessionId"])])
data class AgentModelInvocationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val assistantEntryId: String,
    val timestamp: Long,
    val modelId: String,
    val providerId: String?,
)

@Entity(tableName = "app_state")
data class AppStateEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "remote_tasks")
data class RemoteTaskEntity(@PrimaryKey val sessionId: String, val taskId: String)

data class ConversationSummary(val id: String, val title: String, val preview: String)

@Dao
interface CompanionDao {
    @Query("SELECT value FROM app_state WHERE `key` = :key")
    suspend fun state(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(value: AppStateEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(value: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversation(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Long)

    @Query("""
        SELECT c.id, c.title,
          COALESCE((SELECT m.content FROM conversation_messages m
                    WHERE m.sessionId = c.id ORDER BY m.id DESC LIMIT 1), '') AS preview
        FROM conversations c WHERE c.title <> '' ORDER BY c.updatedAt DESC
    """)
    suspend fun conversations(): List<ConversationSummary>

    @Query("SELECT * FROM conversation_messages WHERE sessionId = :sessionId ORDER BY id")
    suspend fun messages(sessionId: String): List<ConversationMessageEntity>

    @Insert
    suspend fun insertMessage(value: ConversationMessageEntity): Long

    @Query("UPDATE conversation_messages SET content = :content WHERE id = :id AND role = 'tool'")
    suspend fun updateToolMessage(id: Long, content: String)

    @Query("SELECT * FROM agent_sessions WHERE id = :id")
    suspend fun agentSession(id: String): AgentSessionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAgentSession(value: AgentSessionEntity): Long

    @Query("UPDATE agent_sessions SET leafId = :leafId WHERE id = :id")
    suspend fun updateLeaf(id: String, leafId: String?)

    @Query("SELECT * FROM agent_entries WHERE sessionId = :sessionId ORDER BY position")
    suspend fun agentEntries(sessionId: String): List<AgentEntryEntity>

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM agent_entries WHERE sessionId = :sessionId")
    suspend fun nextAgentPosition(sessionId: String): Long

    @Query("SELECT COUNT(*) FROM agent_entries WHERE sessionId = :sessionId AND id = :entryId")
    suspend fun hasAgentEntry(sessionId: String, entryId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAgentEntry(value: AgentEntryEntity): Long

    @Query("SELECT * FROM agent_usages WHERE sessionId = :sessionId ORDER BY rowid")
    suspend fun agentUsages(sessionId: String): List<AgentUsageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAgentUsage(value: AgentUsageEntity): Long

    @Query("SELECT * FROM agent_model_invocations WHERE sessionId = :sessionId ORDER BY rowid")
    suspend fun agentModelInvocations(sessionId: String): List<AgentModelInvocationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAgentModelInvocation(value: AgentModelInvocationEntity): Long

    @Query("SELECT taskId FROM remote_tasks WHERE sessionId = :sessionId")
    suspend fun remoteTaskId(sessionId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRemoteTask(value: RemoteTaskEntity)

    @Query("DELETE FROM remote_tasks WHERE sessionId = :sessionId")
    suspend fun clearRemoteTask(sessionId: String)
}

@Database(
    entities = [ConversationEntity::class, ConversationMessageEntity::class,
        AgentSessionEntity::class, AgentEntryEntity::class, AppStateEntity::class,
        RemoteTaskEntity::class, AgentUsageEntity::class, AgentModelInvocationEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun dao(): CompanionDao

    companion object {
        @Volatile private var instance: CompanionDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `agent_usages` (
                    `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `assistantEntryId` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL, `inputTokens` INTEGER, `outputTokens` INTEGER,
                    `totalTokens` INTEGER, `cachedInputTokens` INTEGER, PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_usages_sessionId` ON `agent_usages` (`sessionId`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `agent_model_invocations` (
                    `id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `assistantEntryId` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL, `modelId` TEXT NOT NULL, `providerId` TEXT,
                    PRIMARY KEY(`id`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_model_invocations_sessionId` ON `agent_model_invocations` (`sessionId`)")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation_messages ADD COLUMN previewImages TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun get(context: Context): CompanionDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CompanionDatabase::class.java,
                "kiora-companion.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
