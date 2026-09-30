package com.example.ahdcamera

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class MediaType { PHOTO, VIDEO }

data class MediaItem(
    val id: Long,
    val uriPath: String,
    val name: String,
    val type: MediaType,
    val dateModified: Long,
    val size: Long
)

class MediaGalleryManager(private val context: Context) {

    private fun isFileExistingAndValid(filePath: String?, contentUri: Uri): Boolean {
        if (!filePath.isNullOrEmpty()) {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) return false
        }
        return try {
            context.contentResolver.openFileDescriptor(contentUri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun loadRecentMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>()

        // 1. Scan Photos from MediaStore
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA
            )
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("IMG_%")
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                var count = 0
                while (cursor.moveToNext() && count < 15) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: continue
                    val date = cursor.getLong(dateColumn) * 1000L
                    val size = cursor.getLong(sizeColumn)
                    val filePath = cursor.getString(dataColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    if (isFileExistingAndValid(filePath, contentUri)) {
                        if (mediaList.none { it.name == name }) {
                            mediaList.add(
                                MediaItem(
                                    id = id,
                                    uriPath = contentUri.toString(),
                                    name = name,
                                    type = MediaType.PHOTO,
                                    dateModified = date,
                                    size = size
                                )
                            )
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Scan Videos from MediaStore
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA
            )
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("VID_%")
            val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                var count = 0
                while (cursor.moveToNext() && count < 15) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: continue
                    val date = cursor.getLong(dateColumn) * 1000L
                    val size = cursor.getLong(sizeColumn)
                    val filePath = cursor.getString(dataColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    if (isFileExistingAndValid(filePath, contentUri)) {
                        if (mediaList.none { it.name == name }) {
                            mediaList.add(
                                MediaItem(
                                    id = id,
                                    uriPath = contentUri.toString(),
                                    name = name,
                                    type = MediaType.VIDEO,
                                    dateModified = date,
                                    size = size
                                )
                            )
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Scan Public Storage & App Folders as Fallback
        val publicPicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AHDCamera")
        val appPicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AHDCamera")

        listOf(publicPicDir, appPicDir).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png") }?.forEach { f ->
                    if (f.exists() && f.length() > 0L && mediaList.none { it.name == f.name }) {
                        mediaList.add(
                            MediaItem(
                                id = f.hashCode().toLong(),
                                uriPath = f.absolutePath,
                                name = f.name,
                                type = MediaType.PHOTO,
                                dateModified = f.lastModified(),
                                size = f.length()
                            )
                        )
                    }
                }
            }
        }

        val publicVidDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AHDCamera")
        val appVidDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AHDCamera")

        listOf(publicVidDir, appVidDir).forEach { dir ->
            if (dir.exists()) {
                dir.listFiles()?.filter { it.extension.lowercase() in listOf("mp4", "mkv") }?.forEach { f ->
                    if (f.exists() && f.length() > 0L && mediaList.none { it.name == f.name }) {
                        mediaList.add(
                            MediaItem(
                                id = f.hashCode().toLong(),
                                uriPath = f.absolutePath,
                                name = f.name,
                                type = MediaType.VIDEO,
                                dateModified = f.lastModified(),
                                size = f.length()
                            )
                        )
                    }
                }
            }
        }

        mediaList.sortedByDescending { it.dateModified }
    }
}
