package local.signaldock.internal

import androidx.room.Database
import androidx.room.RoomDatabase
import local.signaldock.internal.storage.EventEntity
import local.signaldock.internal.storage.QueueDao
import local.signaldock.internal.storage.RejectionEntity
import local.signaldock.internal.storage.RetryStateEntity

// Stays in `local.signaldock.internal`: Room exports the schema to schemas/<fully qualified class name>/,
// so moving this class would rename the committed schema directory. Everything else is in storage.
@Database(
    entities = [EventEntity::class, RejectionEntity::class, RetryStateEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class SignalDockDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
}
