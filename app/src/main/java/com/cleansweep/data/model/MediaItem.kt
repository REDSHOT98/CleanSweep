package com.cleansweep.data.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
data class MediaItem(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val dateAdded: Long,
    val dateModified: Long,
    val size: Long,
    val bucketId: String,
    val bucketName: String,
    val isVideo: Boolean,
    val width: Int,
    val height: Int
) : Parcelable {
    val isImage: Boolean
        get() = !isVideo

    val filePath: String
        get() = when (uri.scheme) {
            "file" -> uri.path ?: ""
            else -> ""
        }

    val file: File
        get() = if (filePath.isNotEmpty()) File(filePath) else File("")
}