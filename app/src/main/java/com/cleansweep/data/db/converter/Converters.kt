package com.cleansweep.data.db.converter

import android.net.Uri
import androidx.core.net.toUri
import androidx.room.TypeConverter

class Converters {
    private val listSeparator = "|||---|||" // A unique separator less likely to be in a file path

    @TypeConverter
    fun fromStringList(list: List<String>): String {
        return list.joinToString(separator = listSeparator)
    }

    @TypeConverter
    fun toStringList(data: String): List<String> {
        return if (data.isEmpty()) emptyList() else data.split(listSeparator)
    }

    @TypeConverter
    fun fromUri(uri: Uri?): String? {
        return uri?.toString()
    }

    @TypeConverter
    fun toUri(uriString: String?): Uri? {
        return uriString?.toUri()
    }
}
