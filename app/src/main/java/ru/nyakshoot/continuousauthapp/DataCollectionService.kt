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
import ru.nyakshoot.continuousauthapp.pipeline.AuthPipeline
import ru.nyakshoot.continuousauthapp.pipeline.ContextSnapshot
import ru.nyakshoot.continuousauthapp.pipeline.FeatureExtractor
import java.io.FileWriter
import androidx.core.content.edit

class DataCollectionService : Service() {

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor:  Sensor? = null
    private var logger:       SessionLogger? = null
    private val handler = Handler(Looper.getMainLooper())
    private var userType: String = "owner"
    private var scenario: String = "static"

    // ── Pipeline ──────────────────────────────────────────────────────────────
    private var authPipeline: AuthPipeline? = null

    /** Последние значения гироскопа — обновляются в onSensorChanged. */
    private val lastGyro = FloatArray(3)

    /** Последний снимок контекста — обновляется каждые CONTEXT_SAMPLE_INTERVAL_MS. */
    @Volatile
    private var lastContextSnapshot = ContextSnapshot(
        scenario            = "static",
        isCharging          = false,
        batteryLevel        = 100f,
        isScreenInteractive = true,
        networkType         = "none",
        isLandscape         = false,
    )

    // ── Таймеры ───────────────────────────────────────────────────────────────

    /** Периодический снимок контекста. */
    private val contextTicker = object : Runnable {
        override fun run() {
            if (!isCollecting) return
            logContextSnapshot("periodic")
            handler.postDelayed(this, CONTEXT_SAMPLE_INTERVAL_MS)
        }
    }

    /** Запуск цикла обработки окна наблюдений (pipeline). */
    private val pipelineTicker = object : Runnable {
        override fun run() {
            if (!isCollecting) return
            authPipeline?.processWindow(lastContextSnapshot)?.let { record ->
                logger?.log("auth_decision", authPipeline!!.recordToJson(record))
                // Уведомляем UI о решении
                sendBroadcast(Intent(ACTION_AUTH_DECISION).apply {
                    putExtra(EXTRA_DECISION,  record.decision.name)
                    putExtra(EXTRA_POSTERIOR, record.posteriorOwner.toFloat())
                })
            }
            handler.postDelayed(this, AuthPipeline.WINDOW_PERIOD_MS)
        }
    }

    // ── SensorEventListener ───────────────────────────────────────────────────

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!isCollecting) return

            val sensorName = when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> "accelerometer"
                Sensor.TYPE_GYROSCOPE     -> "gyroscope"
                else                      -> "unknown"
            }

            // Журналирование (без изменений)
            logger?.log(
                "imu",
                jsonOf(
                    "sensor"               to sensorName,
                    "event_timestamp_nanos" to event.timestamp,
                    "system_time_ms"       to System.currentTimeMillis(),
                    "x"                    to event.values.getOrNull(0),
                    "y"                    to event.values.getOrNull(1),
                    "z"                    to event.values.getOrNull(2),
                ),
            )

            // Передаём в pipeline
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyro[0] = event.values[0]
                    lastGyro[1] = event.values[1]
                    lastGyro[2] = event.values[2]
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    authPipeline?.onImuSample(
                        axX = event.values[0], axY = event.values[1], axZ = event.values[2],
                        gyrX = lastGyro[0], gyrY = lastGyro[1], gyrZ = lastGyro[2],
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (!isCollecting || sensor == null) return
            logger?.log(
                "sensor_accuracy",
                jsonOf(
                    "sensor_type"    to sensor.type,
                    "accuracy"       to accuracy,
                    "system_time_ms" to System.currentTimeMillis(),
                ),
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        activeService = this
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor    = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        createNotificationChannel()
        tryRestoreSessionIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tag              = intent.getStringExtra(EXTRA_SESSION_TAG).orEmpty()
                val incomingUserType = intent.getStringExtra(EXTRA_USER_TYPE).orEmpty().ifBlank { "owner" }
                val incomingScenario = intent.getStringExtra(EXTRA_SCENARIO).orEmpty().ifBlank { "static" }
                startCollection(tag, incomingUserType, incomingScenario)
            }
            ACTION_STOP -> stopCollection("manual_stop")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (isCollecting) stopCollection("service_destroyed")
        activeService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Управление сессией ────────────────────────────────────────────────────

    private fun startCollection(tag: String, incomingUserType: String, incomingScenario: String) {
        if (isCollecting) return
        userType = incomingUserType
        scenario = incomingScenario

        logger = SessionLogger(applicationContext).also {
            it.startNew(tag, userType, scenario)
            currentFilePath = it.currentFilePath
        }

        // Инициализируем pipeline (профили null = режим сбора данных без аутентификации)
        authPipeline = AuthPipeline(
            onDecision = { /* решения уже логируются в pipelineTicker */ }
        )

        startForeground(NOTIFICATION_ID, buildNotification("Collecting data..."))
        registerSensors()
        isCollecting = true
        persistState(true)

        logger?.log("collection_state", jsonOf(
            "state"          to "started",
            "system_time_ms" to System.currentTimeMillis(),
            "user_type"      to userType,
            "scenario"       to scenario,
        ))

        logContextSnapshot("initial")
        handler.postDelayed(contextTicker,  CONTEXT_SAMPLE_INTERVAL_MS)
        handler.postDelayed(pipelineTicker, AuthPipeline.WINDOW_PERIOD_MS)
    }

    private fun stopCollection(reason: String) {
        if (!isCollecting) return

        handler.removeCallbacks(contextTicker)
        handler.removeCallbacks(pipelineTicker)

        authPipeline?.reset()
        authPipeline = null

        sensorManager.unregisterListener(sensorListener)
        logContextSnapshot("final")

        logger?.log("collection_state", jsonOf(
            "state"          to "stopped",
            "reason"         to reason,
            "system_time_ms" to System.currentTimeMillis(),
        ))
        logger?.stop()
        logger = null

        isCollecting    = false
        currentFilePath = null
        persistState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    private fun registerSensors() {
        accelSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun logContextSnapshot(origin: String) {
        if (!isCollecting) return

        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel  = batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100f / scale else null
        }
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging    = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val powerManager  = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isLandscape   = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val orientation   = if (isLandscape) "landscape" else "portrait"
        val networkType   = getNetworkType()

        logger?.log("context", jsonOf(
            "origin"             to origin,
            "system_time_ms"     to System.currentTimeMillis(),
            "scenario_label"     to scenario,
            "user_type"          to userType,
            "orientation"        to orientation,
            "screen_interactive" to powerManager.isInteractive,
            "battery_level_percent" to batteryLevel,
            "is_charging"        to isCharging,
            "network_type"       to networkType,
        ))

        // Обновляем снимок для pipeline
        lastContextSnapshot = ContextSnapshot(
            scenario            = scenario,
            isCharging          = isCharging,
            batteryLevel        = batteryLevel ?: 100f,
            isScreenInteractive = powerManager.isInteractive,
            networkType         = networkType,
            isLandscape         = isLandscape,
        )
    }

    private fun getNetworkType(): String {
        val cm      = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "none"
        val caps    = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else                                                       -> "other"
        }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DataCollectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ContinuousAuth")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Data collection", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Foreground service for continuous auth data collection" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun persistState(isRunning: Boolean) {
        getSharedPreferences(/* name = */ PREFS_NAME, /* mode = */ MODE_PRIVATE).edit {
            putBoolean(KEY_IS_COLLECTING, isRunning)
                .putString(KEY_CURRENT_FILE, currentFilePath)
                .putString(KEY_USER_TYPE, userType)
                .putString(KEY_SCENARIO, scenario)
        }
    }

    private fun tryRestoreSessionIfNeeded() {
        val prefs       = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val shouldRestore = prefs.getBoolean(KEY_IS_COLLECTING, false)
        val savedPath   = prefs.getString(KEY_CURRENT_FILE, null)
        if (!shouldRestore || savedPath.isNullOrBlank()) return

        userType = prefs.getString(KEY_USER_TYPE, "owner").orEmpty().ifBlank { "owner" }
        scenario = prefs.getString(KEY_SCENARIO,  "static").orEmpty().ifBlank { "static" }

        logger = SessionLogger(applicationContext).also {
            it.resume(savedPath, userType, scenario)
            currentFilePath = it.currentFilePath
        }
        authPipeline = AuthPipeline()

        startForeground(NOTIFICATION_ID, buildNotification("Collecting data (recovered)..."))
        registerSensors()
        isCollecting = true
        handler.postDelayed(contextTicker,  CONTEXT_SAMPLE_INTERVAL_MS)
        handler.postDelayed(pipelineTicker, AuthPipeline.WINDOW_PERIOD_MS)

        logger?.log("collection_state", jsonOf(
            "state"          to "recovered_after_restart",
            "system_time_ms" to System.currentTimeMillis(),
        ))
    }

    private fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in pairs) {
            when (value) {
                null    -> json.put(key, JSONObject.NULL)
                is Float -> json.put(key, value.toDouble())
                else    -> json.put(key, value)
            }
        }
        return json
    }

    // ── Companion object ──────────────────────────────────────────────────────

    companion object {
        private const val ACTION_START       = "ru.nyakshoot.continuousauthapp.action.START"
        private const val ACTION_STOP        = "ru.nyakshoot.continuousauthapp.action.STOP"
        const val ACTION_AUTH_DECISION       = "ru.nyakshoot.continuousauthapp.AUTH_DECISION"
        const val EXTRA_DECISION             = "decision"
        const val EXTRA_POSTERIOR            = "posterior"
        private const val EXTRA_SESSION_TAG  = "session_tag"
        private const val EXTRA_USER_TYPE    = "user_type"
        private const val EXTRA_SCENARIO     = "scenario"
        private const val CHANNEL_ID         = "data_collection_channel"
        private const val NOTIFICATION_ID    = 401
        private const val CONTEXT_SAMPLE_INTERVAL_MS = 5_000L

        private const val PREFS_NAME         = "collector_service_state"
        private const val KEY_IS_COLLECTING  = "is_collecting"
        private const val KEY_CURRENT_FILE   = "current_file"
        private const val KEY_USER_TYPE      = "user_type"
        private const val KEY_SCENARIO       = "scenario"

        @Volatile var activeService: DataCollectionService? = null
            private set
        @Volatile private var isCollecting:  Boolean = false
        @Volatile private var currentFilePath: String? = null

        fun start(context: Context, sessionTag: String, userType: String, scenario: String) {
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_TAG, sessionTag)
                putExtra(EXTRA_USER_TYPE,   userType)
                putExtra(EXTRA_SCENARIO,    scenario)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DataCollectionService::class.java).apply { action = ACTION_STOP }
            )
        }

        /**
         * Логирует UI-событие и при ACTION_UP передаёт touch в pipeline.
         *
         * Трекинг start-координат: MainActivity хранит downX/downY и передаёт
         * их при вызове onTouchUp (см. MainActivity.setupTouchCollector).
         */
        fun logUiEvent(context: Context, type: String, payload: JSONObject) {
            activeService?.logger?.log(type, payload)
                ?: appendDirectIfSessionRunning(context, type, payload)
        }

        /**
         * Вызывается из MainActivity при ACTION_UP завершённого касания.
         * @param startX/startY  координаты ACTION_DOWN (сохраняются в MainActivity)
         */
        fun onTouchUp(
            x: Float, y: Float,
            startX: Float, startY: Float,
            pressure: Float, size: Float,
            downTimeMs: Long, eventTimeMs: Long,
        ) {
            activeService?.authPipeline?.onTouchUp(
                x = x, y = y, startX = startX, startY = startY,
                pressure = pressure, size = size,
                downTimeMs = downTimeMs, eventTimeMs = eventTimeMs,
            )
        }

        fun isCollecting(): Boolean  = isCollecting
        fun currentFilePath(): String? = currentFilePath

        private fun appendDirectIfSessionRunning(context: Context, type: String, payload: JSONObject) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_IS_COLLECTING, false)) return
            val path = prefs.getString(KEY_CURRENT_FILE, null) ?: return
            val row  = JSONObject().apply {
                put("type",         type)
                put("payload",      payload)
                put("logged_at_ms", System.currentTimeMillis())
            }
            synchronized(FILE_APPEND_LOCK) {
                runCatching {
                    FileWriter(path, true).use { it.append(row.toString()).append('\n') }
                }
            }
        }

        private val FILE_APPEND_LOCK = Any()
    }
}