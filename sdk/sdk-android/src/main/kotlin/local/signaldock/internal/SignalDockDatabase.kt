package local.signaldock.internal

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.storage.QueueDao
import local.signaldock.internal.storage.RejectionEntity
import local.signaldock.internal.storage.RetryStateEntity

// Stays in `local.signaldock.internal`: Room exports the schema to schemas/<fully qualified class name>/,
// so moving this class would rename the committed schema directory. Everything else is in storage.
@Database(
    entities = [EventEntity::class, RejectionEntity::class, RetryStateEntity::class],
    version = 2,
    exportSchema = true,
)
internal abstract class SignalDockDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
}

/** v0.1.0 shipped schema 1. Adds `retry_state.waitMillis` (null: no clamp). ADR-0025. No destructive fallback. */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE retry_state ADD COLUMN waitMillis INTEGER")
    }
}
