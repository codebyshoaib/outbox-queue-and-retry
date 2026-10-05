package ai.hellorumi.outbox

import ai.hellorumi.outbox.core.Message
import ai.hellorumi.outbox.core.OutboxRow
import ai.hellorumi.outbox.core.Status
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val body: String,
    val createdAt: Long,
    val status: Status,
    val serverSeq: Long?,
) {
    fun toMessage() = Message(id, chatId, body, createdAt, status, serverSeq)
}

/** [key] is autoGenerate, so ordering by it is insertion order (P2 "oldest first"). */
@Entity(tableName = "outbox", indices = [Index("messageId", unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val key: Long = 0,
    val messageId: String,
    val chatId: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
) {
    fun toRow() = OutboxRow(messageId, chatId, attempts, nextAttemptAt, createdAt)
}

data class QueuedMessage(
    @Embedded val row: OutboxEntity,
    @Relation(parentColumn = "messageId", entityColumn = "id") val message: MessageEntity,
)

@Dao
interface OutboxDao {
    @Insert fun insertMessage(m: MessageEntity)
    @Insert fun insertRow(r: OutboxEntity)

    @Transaction
    @Query("SELECT * FROM outbox ORDER BY `key`")
    fun queued(): List<QueuedMessage>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun message(id: String): MessageEntity?

    /** rowid is the insertion order of messages (their primary key is a UUID string). */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY rowid")
    fun observe(chatId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = 'SENT', serverSeq = :seq WHERE id = :id")
    fun setSent(id: String, seq: Long)

    @Query("UPDATE messages SET status = :to WHERE id = :id AND status = :from")
    fun setStatusIf(id: String, from: Status, to: Status): Int

    @Query("UPDATE outbox SET attempts = :attempts, nextAttemptAt = :nextAttemptAt WHERE messageId = :id")
    fun reschedule(id: String, attempts: Int, nextAttemptAt: Long)

    @Query("UPDATE outbox SET nextAttemptAt = :now WHERE nextAttemptAt > :now")
    fun makeAllDue(now: Long)

    @Query("DELETE FROM outbox WHERE messageId = :id")
    fun deleteRow(id: String)
}

@Database(entities = [MessageEntity::class, OutboxEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): OutboxDao

    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, AppDb::class.java, "outbox.db").build()
    }
}
