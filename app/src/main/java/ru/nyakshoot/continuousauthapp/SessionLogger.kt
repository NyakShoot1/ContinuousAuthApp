package ru.nyakshoot.continuousauthapp

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionLogger(private val appContext: Context) {
    private var writer: BufferedWriter? = null
    var currentFilePath: String? = null
        private set

    @Synchronized
    fun startNew(tag: String, userType: String, scenario: String) {
        val folder = resolveSessionFolder()
        if (!folder.exists()) folder.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTag = tag.ifBlank { "untagged" }.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val file = File(folder, "session_${timestamp}_${safeTag}.jsonl")
        writer = BufferedWriter(FileWriter(file, true))
        currentFilePath = file.absolutePath
        log(
            "session_start",
            JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis())
                put("session_tag", tag)
                put("user_type", userType)
                put("scenario", scenario)
                put("app_version", "1.0")
            },
        )
    }

    @Synchronized
    fun resume(existingPath: String, userType: String, scenario: String) {
        val file = File(existingPath)
        file.parentFile?.mkdirs()
        writer = BufferedWriter(FileWriter(file, true))
        currentFilePath = file.absolutePath
        log(
            "session_recovered",
            JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis())
                put("user_type", userType)
                put("scenario", scenario)
            },
        )
    }

    private fun resolveSessionFolder(): File {
        val sharedRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "ContinuousAuthShared/sessions",
        )
        return runCatching {
            if (!sharedRoot.exists()) {
                sharedRoot.mkdirs()
            }
            File(sharedRoot, ".probe").apply {
                writeText("ok")
                delete()
            }
            sharedRoot
        }.getOrElse {
            File(appContext.getExternalFilesDir(null), "sessions")
        }
    }

    @Synchronized
    fun log(type: String, payload: JSONObject) {
        val row = JSONObject().apply {
            put("type", type)
            put("payload", payload)
            put("logged_at_ms", System.currentTimeMillis())
        }
        writer?.apply {
            write(row.toString())
            newLine()
            flush()
        }
    }

    @Synchronized
    fun stop() {
        log(
            "session_end",
            JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis())
            },
        )
        writer?.close()
        writer = null
    }
}
