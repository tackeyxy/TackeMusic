package com.tacke.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.RecentPlay

@Database(
    entities = [DownloadTaskEntity::class, PlaylistSong::class, PlaylistEntity::class, PlaylistSongCrossRef::class, RecentPlay::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun playlistSongDao(): PlaylistSongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentPlayDao(): RecentPlayDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tacke_music_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
