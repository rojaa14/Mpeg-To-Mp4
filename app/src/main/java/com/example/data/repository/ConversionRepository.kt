package com.example.data.repository

import com.example.data.database.ConversionDao
import com.example.data.database.ConversionHistory
import kotlinx.coroutines.flow.Flow

class ConversionRepository(private val conversionDao: ConversionDao) {
    val allConversions: Flow<List<ConversionHistory>> = conversionDao.getAllConversions()

    suspend fun insert(conversion: ConversionHistory): Long {
        return conversionDao.insertConversion(conversion)
    }

    suspend fun deleteById(id: Int) {
        conversionDao.deleteConversionById(id)
    }

    suspend fun clearAll() {
        conversionDao.clearAllConversions()
    }
}
