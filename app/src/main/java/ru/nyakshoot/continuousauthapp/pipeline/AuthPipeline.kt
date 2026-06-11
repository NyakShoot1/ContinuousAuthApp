package ru.nyakshoot.continuousauthapp.pipeline

import android.util.Log
import org.json.JSONObject

/**
 * Главный пайплайн непрерывной аутентификации.
 *
 * Цикл обработки одного окна (раздел 3.2 диплома):
 *   Сырые данные → Предобработка → Признаки → Модальные оценки
 *   → Байесовское слияние → Решение → Журнал
 *
 * Запускается из DataCollectionService по таймеру каждые WINDOW_PERIOD_MS.
 * Все вычисления выполняются локально без передачи данных на сервер.
 */
class AuthPipeline(
    private val config: BayesianFusionConfig = BayesianFusionConfig(),
    imuProfile: ImuModalityModel.ImuOwnerProfile? = null,
    touchProfile: TouchModalityModel.TouchOwnerProfile? = null,
    contextProfile: ContextModalityModel.ContextOwnerProfile? = null,
    private val onDecision: (DecisionRecord) -> Unit = {},
) {

    companion object {
        private const val TAG = "AuthPipeline"
        /** Период запуска цикла — 2 секунды (размер окна наблюдений). */
        const val WINDOW_PERIOD_MS = 2_000L
        const val IMU_SAMPLE_RATE_HZ = 50
        /** Минимальная доля заполнения буфера для запуска обработки. */
        private const val MIN_FILL_FRACTION = 0.6f
    }

    // ── Модули пайплайна ──────────────────────────────────────────────────────
    private val preprocessor = SignalPreprocessor(sampleRateHz = IMU_SAMPLE_RATE_HZ)
    private val imuModel      = ImuModalityModel(imuProfile)
    private val touchModel    = TouchModalityModel(touchProfile)
    private val contextModel  = ContextModalityModel(contextProfile)
    private val fusion        = BayesianFusion(config)
    private val decisionEngine = DecisionEngine(config)

    // ── Буферы сырых данных ───────────────────────────────────────────────────
    private val imuBuffer   = ImuBuffer()
    private val touchBuffer = mutableListOf<FeatureExtractor.TouchEvent>()

    // ── Состояние ─────────────────────────────────────────────────────────────
    private var windowIndex = 0L
    /** Время последнего touch-события — для расчёта deltaInterEventMs. */
    private var lastTouchEventMs: Long = 0L

    // ── Приём данных ──────────────────────────────────────────────────────────

    /**
     * Добавляет IMU-измерение в кольцевой буфер.
     * Вызывается из SensorEventListener при каждом событии акселерометра.
     * Значения гироскопа передаются совместно (последние известные).
     */
    fun onImuSample(
        axX: Float, axY: Float, axZ: Float,
        gyrX: Float, gyrY: Float, gyrZ: Float,
    ) = imuBuffer.add(axX, axY, axZ, gyrX, gyrY, gyrZ)

    /**
     * Добавляет завершённое touch-событие в буфер.
     * Вызывается из DataCollectionService при ACTION_UP.
     *
     * @param x, y         координаты отпускания
     * @param startX, startY координаты начала касания (ACTION_DOWN)
     * @param pressure     давление в момент ACTION_UP
     * @param size         размер пятна
     * @param downTimeMs   системное время ACTION_DOWN
     * @param eventTimeMs  системное время ACTION_UP
     */
    fun onTouchUp(
        x: Float, y: Float,
        startX: Float, startY: Float,
        pressure: Float,
        size: Float,
        downTimeMs: Long,
        eventTimeMs: Long,
    ) {
        val durationMs = eventTimeMs - downTimeMs
        val dx = x - startX
        val dy = y - startY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        // Свайп: расстояние > 20px, тап — меньше
        val isTap = distance < 20f

        val delta = if (lastTouchEventMs == 0L) null else eventTimeMs - lastTouchEventMs
        lastTouchEventMs = eventTimeMs

        val event = FeatureExtractor.TouchEvent(
            timestampMs        = eventTimeMs,
            x                  = x,
            y                  = y,
            startX             = startX,
            startY             = startY,
            distance           = distance,
            pressure           = pressure,
            size               = size,
            durationMs         = durationMs,
            isTap              = isTap,
            deltaInterEventMs  = delta,
        )
        synchronized(touchBuffer) { touchBuffer.add(event) }
    }

    // ── Обработка окна ────────────────────────────────────────────────────────

    /**
     * Запускает один цикл обработки окна наблюдений.
     * Возвращает null если данных недостаточно (буфер не заполнен).
     *
     * @param contextSnapshot  снимок контекста из DataCollectionService
     */
    fun processWindow(contextSnapshot: ContextSnapshot): DecisionRecord? {
        val imuSnap = imuBuffer.snapshot()
        val touchSnap = synchronized(touchBuffer) {
            touchBuffer.toList().also { touchBuffer.clear() }
        }

        // Проверка минимальной заполненности IMU-буфера
        val expectedSamples = (IMU_SAMPLE_RATE_HZ * (WINDOW_PERIOD_MS / 1000.0)).toInt()
        if (imuSnap.accX.size < expectedSamples * MIN_FILL_FRACTION) {
            Log.d(TAG, "Window $windowIndex skipped: IMU ${imuSnap.accX.size}/$expectedSamples")
            return null
        }

        val startMs = System.currentTimeMillis()

        // ── 1. Предобработка IMU ──────────────────────────────────────────────
        // Оценку качества снимаем ДО нормализации — нули в сыром сигнале = пропуски
        val rawAccX = floatListToDoubleArray(imuSnap.accX)
        val rawAccY = floatListToDoubleArray(imuSnap.accY)
        val rawAccZ = floatListToDoubleArray(imuSnap.accZ)

        // Качество оценивается по сырым данным (до Z-score)
        val imuQuality = preprocessor.assessQuality(rawAccX)

        val accXWindows  = preprocessor.preprocessImuAxis(rawAccX)
        val accYWindows  = preprocessor.preprocessImuAxis(rawAccY)
        val accZWindows  = preprocessor.preprocessImuAxis(rawAccZ)
        val gyroXWindows = preprocessor.preprocessImuAxis(floatListToDoubleArray(imuSnap.gyrX))
        val gyroYWindows = preprocessor.preprocessImuAxis(floatListToDoubleArray(imuSnap.gyrY))
        val gyroZWindows = preprocessor.preprocessImuAxis(floatListToDoubleArray(imuSnap.gyrZ))

        fun lastSamples(
            list: List<Pair<SignalPreprocessor.Window, SignalPreprocessor.WindowQuality>>
        ) = list.lastOrNull()?.first?.samples ?: DoubleArray(0)

        // ── 2. Извлечение IMU-признаков ───────────────────────────────────────
        val imuFeatures = FeatureExtractor.extractImuFeatures(
            accX        = lastSamples(accXWindows),
            accY        = lastSamples(accYWindows),
            accZ        = lastSamples(accZWindows),
            gyroX       = lastSamples(gyroXWindows),
            gyroY       = lastSamples(gyroYWindows),
            gyroZ       = lastSamples(gyroZWindows),
            sampleRateHz = IMU_SAMPLE_RATE_HZ,
        )

        // ── 3. Извлечение Touch-признаков ─────────────────────────────────────
        val touchQuality = SignalPreprocessor.WindowQuality(
            fillRatio    = if (touchSnap.isEmpty()) 0.2f else 1.0f,
            outlierRatio = 0f,
            score        = if (touchSnap.isEmpty()) 0.2f else 0.9f,
        )
        val touchFeatures = FeatureExtractor.extractTouchFeatures(touchSnap)

        // ── 4. Контекстные признаки ───────────────────────────────────────────
        val contextFeatures = FeatureExtractor.buildContextFeatures(
            scenario          = contextSnapshot.scenario,
            isCharging        = contextSnapshot.isCharging,
            batteryLevel      = contextSnapshot.batteryLevel,
            isScreenInteractive = contextSnapshot.isScreenInteractive,
            networkType       = contextSnapshot.networkType,
            isLandscape       = contextSnapshot.isLandscape,
        )
        val ctxQuality = SignalPreprocessor.WindowQuality(
            fillRatio = 1f, outlierRatio = 0f, score = 1f
        )

        // ── 5. Модальные оценки ───────────────────────────────────────────────
        val imuResult     = imuModel.evaluate(imuFeatures, imuQuality)
        val touchResult   = touchModel.evaluate(touchFeatures, touchQuality)
        val contextResult = contextModel.evaluate(contextFeatures, ctxQuality)

        // ── 6. Байесовское слияние ────────────────────────────────────────────
        val fusionInput = FusionInput(
            imuResult       = imuResult,
            touchResult     = touchResult,
            contextResult   = contextResult,
            activityContext = contextFeatures.activityContext,
            windowIndex     = windowIndex,
        )
        val fusionOutput = fusion.compute(fusionInput)

        // ── 7. Решение ────────────────────────────────────────────────────────
        val record    = decisionEngine.decide(fusionOutput, fusionInput)
        val latencyMs = System.currentTimeMillis() - startMs

        Log.i(TAG, "w=$windowIndex p=%.3f %s %dms".format(
            fusionOutput.posteriorOwner, record.decision, latencyMs))

        windowIndex++
        onDecision(record)
        return record
    }

    /** Сериализует DecisionRecord в JSONObject для SessionLogger. */
    fun recordToJson(record: DecisionRecord): JSONObject = JSONObject().apply {
        put("window_index",       record.windowIndex)
        put("timestamp_ms",       record.timestampMs)
        put("posterior_owner",    record.posteriorOwner)
        put("decision",           record.decision.name)
        put("accumulation_count", record.accumulationCount)
        put("effective_weights",  JSONObject().also { fw ->
            record.fusionOutput.effectiveWeights.forEach { (k, v) -> fw.put(k, v) }
        })
        put("weighted_scores", JSONObject().also { sc ->
            record.fusionOutput.weightedScores.forEach { (k, v) -> sc.put(k, v) }
        })
        put("imu_debug",     record.imuDebug)
        put("touch_debug",   record.touchDebug)
        put("context_debug", record.contextDebug)
    }

    /** Конвертирует List<Float> в DoubleArray для передачи в SignalPreprocessor. */
    private fun floatListToDoubleArray(list: List<Float>): DoubleArray =
        DoubleArray(list.size) { i -> list[i].toDouble() }

    fun reset() {
        decisionEngine.reset()
        lastTouchEventMs = 0L
        synchronized(touchBuffer) { touchBuffer.clear() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Вспомогательные структуры
// ─────────────────────────────────────────────────────────────────────────────

/** Снимок контекста среды — передаётся из DataCollectionService в pipeline. */
data class ContextSnapshot(
    val scenario:           String,
    val isCharging:         Boolean,
    val batteryLevel:       Float,
    val isScreenInteractive: Boolean,
    val networkType:        String,
    val isLandscape:        Boolean,
)

/**
 * Кольцевой буфер IMU-данных.
 * Хранит последние maxSamples отсчётов по каждой из 6 осей.
 */
class ImuBuffer(private val maxSamples: Int = 200) {

    private val accXBuf = ArrayDeque<Float>(maxSamples)
    private val accYBuf = ArrayDeque<Float>(maxSamples)
    private val accZBuf = ArrayDeque<Float>(maxSamples)
    private val gyrXBuf = ArrayDeque<Float>(maxSamples)
    private val gyrYBuf = ArrayDeque<Float>(maxSamples)
    private val gyrZBuf = ArrayDeque<Float>(maxSamples)

    @Synchronized
    fun add(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        fun ArrayDeque<Float>.push(v: Float) {
            if (size >= maxSamples) removeFirst()
            addLast(v)
        }
        accXBuf.push(ax); accYBuf.push(ay); accZBuf.push(az)
        gyrXBuf.push(gx); gyrYBuf.push(gy); gyrZBuf.push(gz)
    }

    @Synchronized
    fun snapshot() = ImuSnapshot(
        accX = accXBuf.toList(), accY = accYBuf.toList(), accZ = accZBuf.toList(),
        gyrX = gyrXBuf.toList(), gyrY = gyrYBuf.toList(), gyrZ = gyrZBuf.toList(),
    )
}

data class ImuSnapshot(
    val accX: List<Float>, val accY: List<Float>, val accZ: List<Float>,
    val gyrX: List<Float>, val gyrY: List<Float>, val gyrZ: List<Float>,
)