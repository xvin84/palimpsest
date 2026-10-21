package dev.xvin.palimpsest.data.db

import android.content.Context
import androidx.room.AutoMigration
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
        TrackArtistEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun plays(): PlayEventDao
    abstract fun versions(): VersionDao
    abstract fun playlists(): PlaylistDao
    abstract fun lyrics(): LyricsDao
    abstract fun artists(): ArtistDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "palimpsest.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
