package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.converter.VideoConverterEngine
import com.example.data.database.ConversionHistory
import com.example.data.repository.ConversionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ConversionViewModel(private val repository: ConversionRepository) : ViewModel() {

    // Reactive list of past conversions
    val conversionHistoryList: StateFlow<List<ConversionHistory>> = repository.allConversions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current selected file details
    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow("")
    val selectedFileName: StateFlow<String> = _selectedFileName.asStateFlow()

    private val _selectedFileSize = MutableStateFlow(0L)
    val selectedFileSize: StateFlow<Long> = _selectedFileSize.asStateFlow()

    // Conversion status states
    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    private val _conversionProgress = MutableStateFlow(0f)
    val conversionProgress: StateFlow<Float> = _conversionProgress.asStateFlow()

    private val _conversionStatus = MutableStateFlow("")
    val conversionStatus: StateFlow<String> = _conversionStatus.asStateFlow()

    private val _lastConvertedFilePath = MutableStateFlow<String?>(null)
    val lastConvertedFilePath: StateFlow<String?> = _lastConvertedFilePath.asStateFlow()

    // Selects a file and extracts metadata
    fun selectFile(context: Context, uri: Uri) {
        _selectedFileUri.value = uri
        val resolver = context.contentResolver
        var name = "MPEG_Video.mpg"
        var size = 0L

        val cursor = resolver.query(uri, null, null, null, null)
        cursor?.use { c ->
            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIndex != -1) {
                    name = c.getString(nameIndex)
                }
                if (sizeIndex != -1) {
                    size = c.getLong(sizeIndex)
                }
            }
        }
        _selectedFileName.value = name
        _selectedFileSize.value = size
        _lastConvertedFilePath.value = null
    }

    // Reset current selection
    fun resetSelection() {
        _selectedFileUri.value = null
        _selectedFileName.value = ""
        _selectedFileSize.value = 0L
        _isConverting.value = false
        _conversionProgress.value = 0f
        _conversionStatus.value = ""
        _lastConvertedFilePath.value = null
    }

    // Starts the native local conversion
    fun startConversion(context: Context, customOutputName: String = "") {
        val uri = _selectedFileUri.value ?: return
        val originalName = _selectedFileName.value
        val originalSize = _selectedFileSize.value

        viewModelScope.launch {
            _isConverting.value = true
            _conversionProgress.value = 0f
            _conversionStatus.value = "Menyiapkan penyimpanan lokal..."

            val baseName = if (customOutputName.isNotBlank()) {
                customOutputName
            } else {
                originalName.substringBeforeLast(".")
            }
            val outputFileName = "$baseName.mp4"

            // Save in App's local files directory so it's always accessible and offline-safe
            val outputDir = File(context.filesDir, "Konvert_Outputs")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            val outputFile = File(outputDir, outputFileName)

            val startTime = System.currentTimeMillis()

            val success = VideoConverterEngine.remuxMpegToMp4(
                context = context,
                inputUri = uri,
                outputFile = outputFile,
                progressFlow = _conversionProgress,
                statusFlow = _conversionStatus
            )

            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            val finalSize = if (outputFile.exists()) outputFile.length() else 0L

            val status = if (success) "SUCCESS" else "FAILED"

            // Save to Room DB
            repository.insert(
                ConversionHistory(
                    inputFileName = originalName,
                    inputFilePath = uri.toString(),
                    inputFileSize = originalSize,
                    outputFileName = outputFileName,
                    outputFilePath = outputFile.absolutePath,
                    outputFileSize = finalSize,
                    durationMillis = duration,
                    status = status
                )
            )

            _isConverting.value = false
            if (success) {
                _lastConvertedFilePath.value = outputFile.absolutePath
                _conversionStatus.value = "Konversi Berhasil!"
            } else {
                _conversionStatus.value = "Konversi Gagal. Silakan coba file lain."
            }
        }
    }

    // Delete item from history
    fun deleteHistoryItem(id: Int, filePath: String) {
        viewModelScope.launch {
            repository.deleteById(id)
            try {
                val file = File(filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (ignored: Exception) {}
        }
    }

    // Clear entire history
    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}

class ConversionViewModelFactory(private val repository: ConversionRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConversionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ConversionViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
