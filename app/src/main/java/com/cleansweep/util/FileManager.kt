package com.cleansweep.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

object FileManager {

    /**
     * Moves a file from a source path to a destination folder.
     * This is the NEW way. Beautifully simple.
     * @return The new File object on success, null on failure.
     */
    fun moveFile(sourcePath: String, destinationFolderPath: String): File? {
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) return null

            val destinationFolder = File(destinationFolderPath)
            // Create the destination folder if it doesn't exist.
            destinationFolder.mkdirs()

            val destinationFile = File(destinationFolder, sourceFile.name)

            // The magic happens here. This single line replaces all the old complexity.
            if (sourceFile.renameTo(destinationFile)) {
                destinationFile
            } else {
                null // Move failed for a standard reason (e.g., cross-device move)
            }
        } catch (e: Exception) {
            // Handle standard IOExceptions, SecurityExceptions (though less likely now)
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates a new directory (Album).
     */
    fun createAlbum(path: String, name: String): File? {
        return try {
            val newAlbumDir = File(path, name)
            if (newAlbumDir.mkdirs()) {
                newAlbumDir
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Deletes a file.
     * @return true if the file was deleted successfully, false otherwise.
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Gets the actual file path from a URI content://... if possible.
     * For files on external storage, this converts to direct file paths.
     */
    fun getFilePathFromUri(uri: android.net.Uri): String? {
        return try {
            val uriString = uri.toString()
            when {
                uriString.startsWith("file://") -> {
                    uri.path
                }

                uriString.startsWith("content://media/external/") -> {
                    // For MediaStore URIs, we'll need to query for the actual path
                    // This is a simplified version - in practice you'd need ContentResolver
                    null
                }

                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Converts a Storage Access Framework tree URI (content://) to a raw file path.
     * Returns null if the path cannot be resolved (e.g., from an SD card or cloud provider).
     * This implementation is robust for primary storage, using the official DocumentsContract API.
     */
    fun getPathFromTreeUri(context: Context, treeUri: Uri): String? {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            // This URI is not a tree URI, which can happen.
            return null
        }

        if (DocumentsContract.isDocumentUri(context, treeUri)) {
            val documentId = DocumentsContract.getDocumentId(treeUri)
            val split = documentId.split(":")
            if (split.size > 1) {
                val type = split[0]
                val path = split[1]

                if ("primary".equals(type, ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory().absolutePath}/$path"
                }
            }
        }

        // Fallback for URIs that might not be document URIs but are tree URIs
        val split = treeDocumentId.split(":")
        if (split.size > 1) {
            val type = split[0]
            val path = split[1]

            if ("primary".equals(type, ignoreCase = true)) {
                return "${Environment.getExternalStorageDirectory().absolutePath}/$path"
            }
        }

        // If we reach here, it's a non-primary storage (like an SD card) which we do not support for raw path conversion.
        return null
    }

    /**
     * Checks if a file exists.
     */
    fun fileExists(filePath: String): Boolean {
        return try {
            File(filePath).exists()
        } catch (e: Exception) {
            false
        }
    }
}