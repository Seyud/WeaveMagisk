package io.github.seyud.weave.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.seyud.weave.core.model.su.SuLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.Calendar

@Database(version = 2, entities = [SuLog::class], exportSchema = false)
abstract class SuLogDatabase : RoomDatabase() {

    abstract fun suLogDao(): SuLogDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = with(db) {
                execSQL("ALTER TABLE logs ADD COLUMN target INTEGER NOT NULL DEFAULT -1")
                execSQL("ALTER TABLE logs ADD COLUMN context TEXT NOT NULL DEFAULT ''")
                execSQL("ALTER TABLE logs ADD COLUMN gids TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

@Dao
abstract class SuLogDao(private val db: SuLogDatabase) {

    private val twoWeeksAgo =
        Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, -2) }.timeInMillis

    suspend fun deleteAll() = withContext(Dispatchers.IO) { db.clearAllTables() }

    suspend fun fetchAll(): MutableList<SuLog> {
        deleteOutdated()
        return fetch()
    }

    @Query("SELECT * FROM logs ORDER BY time DESC")
    protected abstract suspend fun fetch(): MutableList<SuLog>

    @Query("SELECT * FROM logs ORDER BY time DESC")
    abstract fun observeAll(): Flow<List<SuLog>>

    /** 观察前先清理过期日志（保留 fetchAll 的 2 周清理语义） */
    fun observeAllClean(): Flow<List<SuLog>> = flow {
        deleteOutdated()
        emitAll(observeAll())
    }

    @Query("DELETE FROM logs WHERE time < :timeout")
    protected abstract suspend fun deleteOutdated(timeout: Long = twoWeeksAgo)

    @Insert
    abstract suspend fun insert(log: SuLog)

}
