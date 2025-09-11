package com.cleansweep.domain.util

object HiddenFileFilter {

    private const val hiddenPrefix = "-"
    private val scanExclusionPrefixes = listOf(".", "_")

    /**
     * Checks if a given file path or name should be considered a "hidden" or system file
     * based on common Android/Linux conventions. Used for UI filtering.
     *
     * @param fileName The name of the file (e.g., ".nomedia", "_thumb.jpg", "-app_img.jpg).
     * @return `true` if the file is considered hidden, `false` otherwise.
     */
    fun toBeHidden(fileName: String): Boolean {
        return fileName.startsWith(hiddenPrefix)
    }

    /**
     * Checks if a full file path should be excluded from a deep scan. This is more aggressive
     * than `isNormallyHidden` as it checks every directory component in the path, not just
     * the final filename. It specifically looks for `.` and `_` prefixes, which almost
     * always denote cache, thumbnail, hidden, or temporary directories/files that are irrelevant for scanning.
     *
     * @param path The full file path (e.g., "/storage/emulated/0/DCIM/.thumbnails/1234.jpg").
     * @return `true` if any part of the path is considered hidden for scanning, `false` otherwise.
     */
    fun isPathExcludedFromScan(path: String): Boolean {
        // Splitting by '/' and filtering out empty strings handles potential leading slashes.
        return path.split('/').any { component ->
            scanExclusionPrefixes.any { prefix -> component.startsWith(prefix) }
        }
    }

    /**
     * Filters a list of file paths, returning only those that are not considered
     * "normally hidden" system files based on their filename. Used for UI filtering.
     *
     * @param paths A list of full file paths.
     * @return A new list containing only user-facing file paths.
     */
    fun filterHiddenFiles(paths: List<String>): List<String> {
        return paths.filterNot { path ->
            val fileName = path.substringAfterLast('/')
            toBeHidden(fileName)
        }
    }
}