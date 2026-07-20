package com.tczerwien.lifts.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room type converters. List<String> columns are stored as JSON arrays via explicit
 * kotlinx.serialization serializers (no reflection).
 */
class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String =
        Json.encodeToString(STRING_LIST_SERIALIZER, value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else Json.decodeFromString(STRING_LIST_SERIALIZER, value)

    private companion object {
        private val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
