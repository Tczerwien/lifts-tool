package com.tczerwien.lifts.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room type converters. List<String> columns are stored as JSON arrays via explicit
 * kotlinx.serialization serializers (no reflection). An empty list is "[]" both ways without the
 * JSON codec: it is the default of the version 2 list columns, so most rows hold it.
 */
class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String =
        if (value.isEmpty()) EMPTY_LIST else Json.encodeToString(STRING_LIST_SERIALIZER, value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isEmpty() || value == EMPTY_LIST) emptyList() else Json.decodeFromString(STRING_LIST_SERIALIZER, value)

    companion object {
        /** An empty list as stored: the `'[]'` default of the version 2 list columns. */
        const val EMPTY_LIST = "[]"

        private val STRING_LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
