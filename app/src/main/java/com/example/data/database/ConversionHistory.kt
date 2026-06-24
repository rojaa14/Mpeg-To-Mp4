package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_history")
data class ConversionHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val inputFileName: String,
    val inputFilePath: String,
    val inputFileSize: Long,
    val outputFileName: String,
    val outputFilePath: String,
    val outputFileSize: Long,
    val durationMillis: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String // "SUCCESS", "FAILED", "PROCESSING"
)
