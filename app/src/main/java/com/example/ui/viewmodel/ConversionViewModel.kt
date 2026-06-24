package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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

    // NEW: Compression mode & Auto-save configurations
    private val _isCompressionMode = MutableStateFlow(false)
    val isCompressionMode: StateFlow<Boolean> = _isCompressionMode.asStateFlow()

    private val _compressionRatio = MutableStateFlow(0.5f) // 0.5f means 50% target compression (Keep high quality, decrease bytes)
    val compressionRatio: StateFlow<Float> = _compressionRatio.asStateFlow()

    private val _autoSaveToDownloads = MutableStateFlow(true) // Save to public downloads automatically after conversion
    val autoSaveToDownloads: StateFlow<Boolean> = _autoSaveToDownloads.asStateFlow()

    fun setCompressionMode(enabled: Boolean) {
        _isCompressionMode.value = enabled
    }

    fun setCompressionRatio(ratio: Float) {
        _compressionRatio.value = ratio
    }

    fun setAutoSaveToDownloads(enabled: Boolean) {
        _autoSaveToDownloads.value = enabled
    }

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

    // Starts the native local conversion or compression
    fun startConversion(context: Context, customOutputName: String = "") {
        val uri = _selectedFileUri.value ?: return
        val originalName = _selectedFileName.value
        val originalSize = _selectedFileSize.value
        val isCompress = _isCompressionMode.value
        val ratio = _compressionRatio.value
        val autoSave = _autoSaveToDownloads.value

        viewModelScope.launch {
            _isConverting.value = true
            _conversionProgress.value = 0f
            _conversionStatus.value = "Menyiapkan penyimpanan lokal..."

            val baseName = if (customOutputName.isNotBlank()) {
                customOutputName
            } else {
                originalName.substringBeforeLast(".")
            }
            val suffix = if (isCompress) "_compressed" else ""
            val outputFileName = "${baseName}${suffix}.mp4"

            // Save in App's local files directory so it's always accessible and offline-safe
            val outputDir = File(context.filesDir, "Konvert_Outputs")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            val outputFile = File(outputDir, outputFileName)

            val startTime = System.currentTimeMillis()

            val success = if (isCompress) {
                VideoConverterEngine.compressVideoWithoutLoweringResolution(
                    context = context,
                    inputUri = uri,
                    outputFile = outputFile,
                    progressFlow = _conversionProgress,
                    statusFlow = _conversionStatus,
                    compressionRatio = ratio
                )
            } else {
                VideoConverterEngine.remuxMpegToMp4(
                    context = context,
                    inputUri = uri,
                    outputFile = outputFile,
                    progressFlow = _conversionProgress,
                    statusFlow = _conversionStatus
                )
            }

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

            if (success && autoSave) {
                _conversionStatus.value = "Menyimpan otomatis ke folder Unduhan..."
                saveFileToDownloads(context, outputFile.absolutePath, outputFileName)
            }

            _isConverting.value = false
            if (success) {
                _lastConvertedFilePath.value = outputFile.absolutePath
                _conversionStatus.value = if (autoSave) "Berhasil disimpan ke folder Unduhan!" else "Konversi Berhasil!"
            } else {
                _conversionStatus.value = "Konversi Gagal. Silakan coba file lain."
            }
        }
    }

    // Direct manual download saving triggers
    fun saveFileToDownloads(context: Context, sourceFilePath: String, outputFileName: String): Uri? {
        try {
            val sourceFile = File(sourceFilePath)
            if (!sourceFile.exists()) return null

            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, outputFileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Konvert")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(collection, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                return uri
            }
        } catch (e: Exception) {
            Log.e("ConversionViewModel", "Gagal menyimpan berkas ke folder Unduhan: ${e.message}", e)
        }
        return null
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
