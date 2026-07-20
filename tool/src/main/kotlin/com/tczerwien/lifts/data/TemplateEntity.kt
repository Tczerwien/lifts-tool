package com.tczerwien.lifts.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A reusable workout template, ordered in the template list by [position]. */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
)
