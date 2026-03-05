package com.tacke.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tacke.music.data.model.PlaylistSong
import com.tacke.music.data.model.PlaylistSongEntity
import com.tacke.music.data.model.RecentPlay

@Database(
    entities = [
        DownloadTaskEntity::class,
        PlaylistSong::class,
        PlaylistSongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        RecentPlay::class,
        FavoriteSongEntity::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun playlistSongDao(): PlaylistSongDao
    abstract fun playlistSongEntityDao(): PlaylistSongEntityDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentPlayDao(): RecentPlayDao
    abstract fun favoriteSongDao(): FavoriteSongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 从版本10迁移到版本11：删除customCoverPath列
        // 由于SQLite不支持直接删除列，需要创建新表并迁移数据
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建新表（不包含customCoverPath列）
                database.execSQL("""
                    CREATE TABLE playlists_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        coverUrl TEXT,
                        createTime INTEGER NOT NULL DEFAULT 0,
                        updateTime INTEGER NOT NULL DEFAULT 0,
                        songCount INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // 从旧表迁移数据
                database.execSQL("""
                    INSERT INTO playlists_new (id, name, description, coverUrl, createTime, updateTime, songCount)
                    SELECT id, name, description, coverUrl, createTime, updateTime, songCount FROM playlists
                """)

                // 删除旧表
                database.execSQL("DROP TABLE playlists")

                // 重命名新表
                database.execSQL("ALTER TABLE playlists_new RENAME TO playlists")
            }
        }

        // 从版本11迁移到版本12：添加iconColor列
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加iconColor列
                database.execSQL("ALTER TABLE playlists ADD COLUMN iconColor TEXT")
            }
        }

        // 从版本12迁移到版本13：添加favorite_songs表
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建favorite_songs表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_songs (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        artists TEXT NOT NULL,
                        coverUrl TEXT,
                        platform TEXT NOT NULL,
                        addedTime INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tacke_music_database"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
