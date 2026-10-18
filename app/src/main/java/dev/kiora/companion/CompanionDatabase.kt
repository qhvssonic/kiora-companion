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

    @Query("SELECT role, content AS text FROM conversation_messages WHERE sessionId = :sessionId ORDER BY id")
    suspend fun messages(sessionId: String): List<ChatLine>

    @Insert
    suspend fun insertMessage(value: ConversationMessageEntity)

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
        RemoteTaskEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CompanionDatabase : RoomDatabase() {
    abstract fun dao(): CompanionDao

    companion object {
        @Volatile private var instance: CompanionDatabase? = null

        fun get(context: Context): CompanionDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CompanionDatabase::class.java,
                "kiora-companion.db").build().also { instance = it }
        }
    }
}
