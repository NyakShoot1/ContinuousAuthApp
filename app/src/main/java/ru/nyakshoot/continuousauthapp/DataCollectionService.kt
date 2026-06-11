package ru.nyakshoot.continuousauthapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.FileWriter

class DataCollectionService : Service() {
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var logger: SessionLogger? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userType: String = "owner"
    private var scenario: String = "static"

    private val contextTicker = object : Runnable {
        override fun run() {
            if (!isCollecting) return
            logContextSnapshot("periodic")
            handler.postDelayed(this, CONTEXT_SAMPLE_INTERVAL_MS)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isCollecting) return
            val sensorName = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> "accelerometer"
                Sensor.TYPE_GYROSCOPE -> "gyroscope"
                else -> "unknown"
            }
            logger?.log(
                "imu",
                jsonOf(
                    "sensor" to sensorName,
                    "event_timestamp_nanos" to event.timestamp,
                    "system_time_ms" to System.currentTimeMillis(),
                    "x" to event.values.getOrNull(0),
                    "y" to event.values.getOrNull(1),
                    "z" to event.values.getOrNull(2),
                ),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (!isCollecting || sensor == null) return
            logger?.log(
                "sensor_accuracy",
                jsonOf(
                    "sensor_type" to sensor.type,
                    "accuracy" to accuracy,
                    "system_time_ms" to System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        createNotificationChannel()
        tryRestoreSessionIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tag = intent.getStringExtra(EXTRA_SESSION_TAG).orEmpty()
                val incomingUserType = intent.getStringExtra(EXTRA_USER_TYPE).orEmpty().ifBlank { "owner" }
                val incomingScenario = intent.getStringExtra(EXTRA_SCENARIO).orEmpty().ifBlank { "static" }
                startCollection(tag, incomingUserType, incomingScenario)
            }

            ACTION_STOP -> stopCollection("manual_stop")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (isCollecting) {
            stopCollection("service_destroyed")
        }
        activeService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCollection(tag: String, incomingUserType: String, incomingScenario: String) {
        if (isCollecting) return
        userType = incomingUserType
        scenario = incomingScenario
        logger = SessionLogger(applicationContext).also {
            it.startNew(tag, userType, scenario)
            currentFilePath = it.currentFilePath
        }

        startForeground(NOTIFICATION_ID, buildNotification("Collecting data..."))
        registerSensors()
        isCollecting = true
        persistState(true)
        logger?.log(
            "collection_state",
            jsonOf(
                "state" to "started",
                "system_time_ms" to System.currentTimeMillis(),
                "user_type" to userType,
                "scenario" to scenario,
            ),
        )
        logContextSnapshot("initial")
        handler.postDelayed(contextTicker, CONTEXT_SAMPLE_INTERVAL_MS)
    }

    private fun stopCollection(reason: String) {
        if (!isCollecting) return
        handler.removeCallbacks(contextTicker)
        sensorManager.unregisterListener(sensorListener)
        logContextSnapshot("final")
        logger?.log(
            "collection_state",
            jsonOf(
                "state" to "stopped",
                "reason" to reason,
                "system_time_ms" to System.currentTimeMillis(),
            ),
        )
        logger?.stop()
        logger = null
        isCollecting = false
        persistState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerSensors() {
        accelSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun logContextSnapshot(origin: String) {
        if (!isCollecting) return
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100f / scale else null
        }
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val orientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        }

        logger?.log(
            "context",
            jsonOf(
                "origin" to origin,
                "system_time_ms" to System.currentTimeMillis(),
                "scenario_label" to scenario,
                "user_type" to userType,
                "orientation" to orientation,
                "screen_interactive" to powerManager.isInteractive,
                "battery_level_percent" to batteryLevel,
                "is_charging" to isCharging,
                "network_type" to getNetworkType(),
            ),
        )
    }

    private fun getNetworkType(): String {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return "none"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun buildNotification(content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, DataCollectionService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ContinuousAuth data collection")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Data collection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Foreground service for long-running sensor collection"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun persistState(isRunning: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(KEY_IS_COLLECTING, isRunning)
            .putString(KEY_CURRENT_FILE, currentFilePath)
            .putString(KEY_USER_TYPE, userType)
            .putString(KEY_SCENARIO, scenario)
            .apply()
    }

    private fun tryRestoreSessionIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val shouldRestore = prefs.getBoolean(KEY_IS_COLLECTING, false)
        val savedPath = prefs.getString(KEY_CURRENT_FILE, null)
        if (!shouldRestore || savedPath.isNullOrBlank()) return

        userType = prefs.getString(KEY_USER_TYPE, "owner").orEmpty().ifBlank { "owner" }
        scenario = prefs.getString(KEY_SCENARIO, "static").orEmpty().ifBlank { "static" }
        logger = SessionLogger(applicationContext).also {
            it.resume(savedPath, userType, scenario)
            currentFilePath = it.currentFilePath
        }
        startForeground(NOTIFICATION_ID, buildNotification("Collecting data (recovered)..."))
        registerSensors()
        isCollecting = true
        handler.postDelayed(contextTicker, CONTEXT_SAMPLE_INTERVAL_MS)
        logger?.log(
            "collection_state",
            jsonOf(
                "state" to "recovered_after_restart",
                "system_time_ms" to System.currentTimeMillis(),
            ),
        )
    }

    private fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in pairs) {
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Float -> json.put(key, value.toDouble())
                else -> json.put(key, value)
            }
        }
        return json
    }

    companion object {
        private const val ACTION_START = "ru.nyakshoot.continuousauthapp.action.START"
        private const val ACTION_STOP = "ru.nyakshoot.continuousauthapp.action.STOP"
        private const val EXTRA_SESSION_TAG = "session_tag"
        private const val EXTRA_USER_TYPE = "user_type"
        private const val EXTRA_SCENARIO = "scenario"
        private const val CHANNEL_ID = "data_collection_channel"
        private const val NOTIFICATION_ID = 401
        private const val CONTEXT_SAMPLE_INTERVAL_MS = 5_000L

        private const val PREFS_NAME = "collector_service_state"
        private const val KEY_IS_COLLECTING = "is_collecting"
        private const val KEY_CURRENT_FILE = "current_file"
        private const val KEY_USER_TYPE = "user_type"
        private const val KEY_SCENARIO = "scenario"

        @Volatile
        private var activeService: DataCollectionService? = null

        @Volatile
        private var isCollecting: Boolean = false

        @Volatile
        private var currentFilePath: String? = null

        fun start(context: Context, sessionTag: String, userType: String, scenario: String) {
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_TAG, sessionTag)
                putExtra(EXTRA_USER_TYPE, userType)
                putExtra(EXTRA_SCENARIO, scenario)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun logUiEvent(context: Context, type: String, payload: JSONObject) {
            activeService?.logger?.log(type, payload) ?: appendDirectIfSessionRunning(context, type, payload)
        }

        fun isCollecting(): Boolean = isCollecting

        fun currentFilePath(): String? = currentFilePath

        private fun appendDirectIfSessionRunning(context: Context, type: String, payload: JSONObject) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_IS_COLLECTING, false)) return
            val path = prefs.getString(KEY_CURRENT_FILE, null) ?: return

            val row = JSONObject().apply {
                put("type", type)
                put("payload", payload)
                put("logged_at_ms", System.currentTimeMillis())
            }
            synchronized(FILE_APPEND_LOCK) {
                runCatching {
                    FileWriter(path, true).use { writer ->
                        writer.append(row.toString())
                        writer.append('\n')
                    }
                }
            }
        }

        private val FILE_APPEND_LOCK = Any()
    }
}
