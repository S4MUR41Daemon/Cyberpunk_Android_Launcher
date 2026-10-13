package com.atmos.launcher.data.db

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks", indices = [Index(value = ["title", "artist"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val pkg: String,
    val playedAt: Long
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["convKey", "time", "sender"], unique = true), Index("convKey")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val convKey: String,
    val title: String,
    val sender: String,
    val text: String,
    val time: Long,
    val fromMe: Boolean,
    val isGroup: Boolean,
    val pkg: String
)

@Immutable
data class ConversationRow(
    val convKey: String,
    val title: String,
    val pkg: String,
    val lastText: String,
    val lastSender: String,
    val lastTime: Long,
    val fromMe: Boolean,
    val isGroup: Boolean
)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TrackEntity)

    @Query("SELECT * FROM tracks ORDER BY playedAt DESC LIMIT 30")
    fun recent(): Flow<List<TrackEntity>>

    @Query("DELETE FROM tracks WHERE id NOT IN (SELECT id FROM tracks ORDER BY playedAt DESC LIMIT 200)")
    suspend fun trim()
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE convKey = :key AND id NOT IN (SELECT id FROM messages WHERE convKey = :key ORDER BY time DESC LIMIT 200)")
    suspend fun trim(key: String)

    @Transaction
    suspend fun ingest(key: String, list: List<MessageEntity>) {
        insertAll(list)
        trim(key)
    }

    @Query("SELECT * FROM messages WHERE convKey = :key ORDER BY time ASC")
    fun conversation(key: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.convKey AS convKey, m.title AS title, m.pkg AS pkg, m.text AS lastText, m.sender AS lastSender,
               m.time AS lastTime, m.fromMe AS fromMe,
               (SELECT MAX(g.isGroup) FROM messages g WHERE g.convKey = m.convKey) AS isGroup
        FROM messages m
        WHERE m.id = (SELECT x.id FROM messages x WHERE x.convKey = m.convKey ORDER BY x.time DESC, x.id DESC LIMIT 1)
        ORDER BY m.time DESC
        """
    )
    fun conversations(): Flow<List<ConversationRow>>
}

@Database(entities = [TrackEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class LauncherDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun messages(): MessageDao

    companion object {
        fun create(ctx: Context) = Room.databaseBuilder(ctx, LauncherDb::class.java, "launcher.db")
            .fallbackToDestructiveMigration()
            .build()
    }
}
