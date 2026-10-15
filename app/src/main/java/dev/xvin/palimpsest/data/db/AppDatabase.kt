package dev.xvin.palimpsest.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlayEventEntity::class,
        TrackVersionEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        LyricsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun plays(): PlayEventDao
    abstract fun versions(): VersionDao
    abstract fun playlists(): PlaylistDao
    abstract fun lyrics(): LyricsDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "palimpsest.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
