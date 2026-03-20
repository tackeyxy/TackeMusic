package com.tacke.music.util

import android.util.Log
import com.mpatric.mp3agic.ID3v1
import com.mpatric.mp3agic.ID3v2
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

object AudioTagHelper {
    private const val TAG = "AudioTagHelper"

    data class AudioMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val year: String? = null
    )

    suspend fun embedMetadata(
        filePath: String,
        metadata: AudioMetadata
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $filePath")
                return@withContext false
            }

            // 使用更可靠的方式获取文件扩展名
            val extension = getFileExtension(filePath)
            Log.d(TAG, "File extension: $extension for path: $filePath")

            when (extension) {
                "mp3" -> embedMp3Metadata(filePath, metadata)
                "flac" -> embedFlacMetadata(filePath, metadata)
                "aac" -> embedAacMetadata(filePath, metadata)
                "m4a" -> embedAacMetadata(filePath, metadata)
                else -> {
                    Log.w(TAG, "Unsupported audio format: '$extension', skipping metadata embedding for: $filePath")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to embed metadata: ${e.message}", e)
            false
        }
    }

    /**
     * 获取文件扩展名（更可靠的实现）
     */
    private fun getFileExtension(filePath: String): String {
        val file = File(filePath)
        val name = file.name
        val lastDotIndex = name.lastIndexOf('.')
        return if (lastDotIndex > 0 && lastDotIndex < name.length - 1) {
            name.substring(lastDotIndex + 1).lowercase()
        } else {
            ""
        }
    }

    private suspend fun embedMp3Metadata(
        filePath: String,
        metadata: AudioMetadata
    ): Boolean {
        return try {
            Log.d(TAG, "Starting MP3 metadata embedding for: $filePath")
            Log.d(TAG, "Metadata - Title: ${metadata.title}, Artist: ${metadata.artist}, Album: ${metadata.album}")

            val mp3File = Mp3File(filePath)
            val hasId3v2Tag = mp3File.hasId3v2Tag()
            val hasId3v1Tag = mp3File.hasId3v1Tag()

            Log.d(TAG, "Existing tags - ID3v2: $hasId3v2Tag, ID3v1: $hasId3v1Tag")

            var id3v2Tag: ID3v2? = if (hasId3v2Tag) mp3File.id3v2Tag else null
            var id3v1Tag: ID3v1? = if (hasId3v1Tag) mp3File.id3v1Tag else null

            if (id3v2Tag == null) {
                id3v2Tag = com.mpatric.mp3agic.ID3v24Tag()
                Log.d(TAG, "Created new ID3v2.4 tag")
            }
            if (id3v1Tag == null) {
                id3v1Tag = com.mpatric.mp3agic.ID3v1Tag()
                Log.d(TAG, "Created new ID3v1 tag")
            }

            // 设置标题
            if (!metadata.title.isNullOrBlank()) {
                id3v2Tag.title = metadata.title
                id3v1Tag.title = metadata.title.take(30)
                Log.d(TAG, "Set title: ${metadata.title}")
            }

            // 设置艺术家
            if (!metadata.artist.isNullOrBlank()) {
                id3v2Tag.artist = metadata.artist
                id3v1Tag.artist = metadata.artist.take(30)
                Log.d(TAG, "Set artist: ${metadata.artist}")
            }

            // 设置专辑
            if (!metadata.album.isNullOrBlank()) {
                id3v2Tag.album = metadata.album
                id3v1Tag.album = metadata.album.take(30)
                Log.d(TAG, "Set album: ${metadata.album}")
            }

            // 设置年份
            if (!metadata.year.isNullOrBlank()) {
                id3v2Tag.year = metadata.year
                id3v1Tag.year = metadata.year
                Log.d(TAG, "Set year: ${metadata.year}")
            }

            mp3File.id3v2Tag = id3v2Tag
            mp3File.id3v1Tag = id3v1Tag

            val tempFile = File("$filePath.tmp")
            Log.d(TAG, "Saving to temp file: ${tempFile.absolutePath}")
            mp3File.save(tempFile.absolutePath)
            Log.d(TAG, "Temp file saved successfully")

            val originalFile = File(filePath)
            Log.d(TAG, "Deleting original file: ${originalFile.absolutePath}")
            if (originalFile.delete()) {
                Log.d(TAG, "Original file deleted, renaming temp file...")
                if (tempFile.renameTo(originalFile)) {
                    Log.d(TAG, "Successfully embedded metadata to: $filePath")
                    true
                } else {
                    Log.e(TAG, "Failed to rename temp file")
                    false
                }
            } else {
                Log.e(TAG, "Failed to delete original file")
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to embed MP3 metadata: ${e.message}", e)
            false
        }
    }

    private suspend fun embedFlacMetadata(
        filePath: String,
        metadata: AudioMetadata
    ): Boolean {
        return try {
            Log.d(TAG, "Starting FLAC metadata embedding for: $filePath")
            Log.d(TAG, "Metadata - Title: ${metadata.title}, Artist: ${metadata.artist}, Album: ${metadata.album}")

            val audioFile = AudioFileIO.read(File(filePath))
            val tag = audioFile.tagOrCreateAndSetDefault

            // 设置标题
            if (!metadata.title.isNullOrBlank()) {
                tag.setField(FieldKey.TITLE, metadata.title)
                Log.d(TAG, "Set title: ${metadata.title}")
            }

            // 设置艺术家
            if (!metadata.artist.isNullOrBlank()) {
                tag.setField(FieldKey.ARTIST, metadata.artist)
                Log.d(TAG, "Set artist: ${metadata.artist}")
            }

            // 设置专辑
            if (!metadata.album.isNullOrBlank()) {
                tag.setField(FieldKey.ALBUM, metadata.album)
                Log.d(TAG, "Set album: ${metadata.album}")
            }

            // 设置年份
            if (!metadata.year.isNullOrBlank()) {
                tag.setField(FieldKey.YEAR, metadata.year)
                Log.d(TAG, "Set year: ${metadata.year}")
            }

            audioFile.commit()
            Log.d(TAG, "Successfully embedded FLAC metadata to: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to embed FLAC metadata: ${e.message}", e)
            false
        }
    }

    /**
     * 嵌入 AAC/M4A 文件元数据
     * AAC 和 M4A 格式使用相同的处理方式（基于 MPEG-4 容器）
     */
    private suspend fun embedAacMetadata(
        filePath: String,
        metadata: AudioMetadata
    ): Boolean {
        return try {
            Log.d(TAG, "Starting AAC/M4A metadata embedding for: $filePath")
            Log.d(TAG, "Metadata - Title: ${metadata.title}, Artist: ${metadata.artist}, Album: ${metadata.album}")

            val audioFile = AudioFileIO.read(File(filePath))
            val tag = audioFile.tagOrCreateAndSetDefault

            // 设置标题
            if (!metadata.title.isNullOrBlank()) {
                tag.setField(FieldKey.TITLE, metadata.title)
                Log.d(TAG, "Set title: ${metadata.title}")
            }

            // 设置艺术家
            if (!metadata.artist.isNullOrBlank()) {
                tag.setField(FieldKey.ARTIST, metadata.artist)
                Log.d(TAG, "Set artist: ${metadata.artist}")
            }

            // 设置专辑
            if (!metadata.album.isNullOrBlank()) {
                tag.setField(FieldKey.ALBUM, metadata.album)
                Log.d(TAG, "Set album: ${metadata.album}")
            }

            // 设置年份
            if (!metadata.year.isNullOrBlank()) {
                tag.setField(FieldKey.YEAR, metadata.year)
                Log.d(TAG, "Set year: ${metadata.year}")
            }

            audioFile.commit()
            Log.d(TAG, "Successfully embedded AAC/M4A metadata to: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to embed AAC/M4A metadata: ${e.message}", e)
            false
        }
    }
}
