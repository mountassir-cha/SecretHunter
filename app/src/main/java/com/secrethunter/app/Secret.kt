package com.secrethunter.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secrets")
data class Secret(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: String,
    val ruleName: String,
    val category: String,
    val severity: String,
    val snippet: String,
    val filePath: String,
    val lineNumber: Int,
    val foundAtMillis: Long,
)
