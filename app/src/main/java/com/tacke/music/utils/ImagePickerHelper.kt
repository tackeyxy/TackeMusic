package com.tacke.music.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * 图片选择器帮助类
 * 用于选择本地图片并保存到应用私有目录
 */
class ImagePickerHelper {

    companion object {
        const val PLAYLIST_COVERS_DIR = "playlist_covers"

        /**
         * 获取歌单封面存储目录
         */
        fun getPlaylistCoversDir(context: Context): File {
            val dir = File(context.filesDir, PLAYLIST_COVERS_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        /**
         * 保存图片到应用私有目录
         * @return 保存后的文件路径
         */
        suspend fun saveImageToPrivateDir(context: Context, sourceUri: Uri): String? {
            return withContext(Dispatchers.IO) {
                try {
                    val coversDir = getPlaylistCoversDir(context)
                    val fileName = "cover_${UUID.randomUUID()}.jpg"
                    val destFile = File(coversDir, fileName)
                    android.util.Log.d("ImagePickerHelper", "Saving image to: ${destFile.absolutePath}")
                    android.util.Log.d("ImagePickerHelper", "Source URI: $sourceUri")

                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        FileOutputStream(destFile).use { outputStream ->
                            val bytesCopied = inputStream.copyTo(outputStream)
                            android.util.Log.d("ImagePickerHelper", "Copied $bytesCopied bytes")
                        }
                    } ?: run {
                        android.util.Log.e("ImagePickerHelper", "Failed to open input stream for URI: $sourceUri")
                        return@withContext null
                    }

                    // 验证文件是否成功保存
                    if (destFile.exists() && destFile.length() > 0) {
                        android.util.Log.d("ImagePickerHelper", "Image saved successfully, size: ${destFile.length()} bytes, path: ${destFile.absolutePath}")
                        destFile.absolutePath
                    } else {
                        android.util.Log.e("ImagePickerHelper", "File was not created or is empty")
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImagePickerHelper", "Error saving image: ${e.message}", e)
                    e.printStackTrace()
                    null
                }
            }
        }

        /**
         * 删除封面图片
         */
        fun deleteCoverImage(coverPath: String?) {
            if (coverPath.isNullOrEmpty()) return
            try {
                val file = File(coverPath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 创建图片选择Intent
         */
        fun createImagePickerIntent(): Intent {
            return Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            }
        }
    }

    private var pickImageLauncher: ActivityResultLauncher<Intent>? = null
    private var onImagePicked: ((Uri?) -> Unit)? = null

    /**
     * 在Activity中注册图片选择器
     */
    fun registerInActivity(activity: AppCompatActivity) {
        pickImageLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onImagePicked?.invoke(result.data?.data)
            } else {
                onImagePicked?.invoke(null)
            }
        }
    }

    /**
     * 在Fragment中注册图片选择器
     */
    fun registerInFragment(fragment: Fragment) {
        pickImageLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onImagePicked?.invoke(result.data?.data)
            } else {
                onImagePicked?.invoke(null)
            }
        }
    }

    /**
     * 启动图片选择器
     */
    fun pickImage(onPicked: (Uri?) -> Unit) {
        onImagePicked = onPicked
        val intent = createImagePickerIntent()
        pickImageLauncher?.launch(intent)
    }
}
