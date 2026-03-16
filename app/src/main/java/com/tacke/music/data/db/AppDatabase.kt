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
        FavoriteSongEntity::class,
        SongDetailEntity::class,
        LocalMusicInfoEntity::class
    ],
    version = 17,
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
    abstract fun songDetailDao(): SongDetailDao
    abstract fun localMusicInfoDao(): LocalMusicInfoDao

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

        // 从版本13迁移到版本14：添加song_details表
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建song_details表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS song_details (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        artists TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        playUrl TEXT NOT NULL,
                        coverUrl TEXT,
                        lyrics TEXT,
                        quality TEXT NOT NULL DEFAULT '320k',
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // 从版本14迁移到版本15：为download_tasks表添加quality字段
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 为download_tasks表添加quality字段
                database.execSQL("ALTER TABLE download_tasks ADD COLUMN quality TEXT NOT NULL DEFAULT '320k'")
            }
        }

        // 从版本15迁移到版本16：添加local_music_info表
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建local_music_info表
                // 注意：不使用DEFAULT值，让Room自己处理默认值
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS local_music_info (
                        id INTEGER PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        path TEXT NOT NULL,
                        picId TEXT,
                        lyricId TEXT,
                        source TEXT,
                        coverUrl TEXT,
                        lyrics TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                // 创建索引
                database.execSQL("CREATE INDEX IF NOT EXISTS index_local_music_info_path ON local_music_info(path)")
            }
        }

        // 从版本16迁移到版本17：修改local_music_info表，添加自增ID和唯一索引
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. 创建新表（使用自增ID和path唯一索引）
                database.execSQL("""
                    CREATE TABLE local_music_info_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT NOT NULL,
                        album TEXT NOT NULL,
                        path TEXT NOT NULL,
                        picId TEXT,
                        lyricId TEXT,
                        source TEXT,
                        coverUrl TEXT,
                        lyrics TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                """)

                // 2. 创建唯一索引
                database.execSQL("CREATE UNIQUE INDEX index_local_music_info_path ON local_music_info_new(path)")

                // 3. 迁移数据（跳过重复的path，保留最新的一条）
                database.execSQL("""
                    INSERT INTO local_music_info_new (title, artist, album, path, picId, lyricId, source, coverUrl, lyrics, updatedAt)
                    SELECT title, artist, album, path, picId, lyricId, source, coverUrl, lyrics, updatedAt
                    FROM local_music_info
                    GROUP BY path
                    HAVING MAX(updatedAt)
                """)

                // 4. 删除旧表
                database.execSQL("DROP TABLE local_music_info")

                // 5. 重命名新表
                database.execSQL("ALTER TABLE local_music_info_new RENAME TO local_music_info")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tacke_music_database"
                )
                    .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
