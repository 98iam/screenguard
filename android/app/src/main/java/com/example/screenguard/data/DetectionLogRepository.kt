package com.example.screenguard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DetectionLog(
    val id: String = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
    val timestamp: String,
    val packageName: String,
    val appName: String,
    val matchedWord: String,
    val actionTaken: String = "App Closed (Home)"
)

object DetectionLogRepository {
    private val _logs = MutableStateFlow<List<DetectionLog>>(emptyList())
    val logs: StateFlow<List<DetectionLog>> = _logs.asStateFlow()

    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    @Synchronized
    fun addLog(packageName: String, appName: String, matchedWord: String) {
        val newLog = DetectionLog(
            timestamp = timeFormat.get()?.format(Date()) ?: "",
            packageName = packageName,
            appName = appName.ifEmpty { packageName },
            matchedWord = matchedWord
        )
        val current = _logs.value.toMutableList()
        current.add(0, newLog)
        if (current.size > 200) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
    }

    @Synchronized
    fun clearLogs() {
        _logs.value = emptyList()
    }
}
