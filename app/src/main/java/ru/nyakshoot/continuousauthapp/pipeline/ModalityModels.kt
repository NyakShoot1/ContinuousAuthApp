package ru.nyakshoot.continuousauthapp.pipeline

import kotlin.math.*

/**
 * Частные модели каналов и расчёт надёжности (reliability).
 *
 * Каждый канал возвращает пару (score, reliability):
 *  - score ∈ [0, 1]  — оценка принадлежности владельцу
 *  - reliability ∈ [0, 1] — качество сигнала в текущем окне
 *
 * В прototипе используются интерпретируемые эвристические модели вместо
 * нейросетей (CNN / LSTM / GRU), так как обученные TFLite-веса формируются
 * отдельно на собранном датасете. Интерфейс ModalityModel позволяет
 * подключить TFLite-модель без изменения пайплайна (см. раздел 3.5 диплома).
 */

// ─────────────────────────────────────────────────────────────────────────────
// Интерфейс модальной модели
// ─────────────────────────────────────────────────────────────────────────────

/** Результат оценки одного канала за одно окно наблюдений. */
data class ModalityResult(
    val score: Double,          // ∈ [0, 1], выше = больше похоже на владельца
    val reliability: Double,    // ∈ [0, 1], выше = лучше качество сигнала
    val debugInfo: String = "",
)

/**
 * Общий интерфейс частной модели.
 * Реализации: [ImuModalityModel], [TouchModalityModel], [ContextModalityModel].
 */
interface ModalityModel<F> {
    /** Идентификатор канала — используется в журнале решений. */
    val channelName: String

    /**
     * Принимает вектор признаков и качество окна,
     * возвращает (score, reliability).
     */
    fun evaluate(features: F, windowQuality: SignalPreprocessor.WindowQuality): ModalityResult
}

// ─────────────────────────────────────────────────────────────────────────────
// IMU-модель (заглушка с детерминированной логикой)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * IMU-модель (акселерометр + гироскоп).
 *
 * Архитектура в полной реализации: 1D-CNN для обнаружения локальных
 * частотных паттернов движения (см. раздел 3.5 диплома).
 *
 * В прototипе: эвристика на основе устойчивости энергетического профиля.
 * Если профиль близок к сохранённому эталону владельца — score высокий.
 *
 * @param ownerProfile  эталонный профиль владельца (загружается из хранилища).
 *                      null = режим начального обучения, score возвращается 0.5.
 */
class ImuModalityModel(
    private val ownerProfile: ImuOwnerProfile? = null,
) : ModalityModel<FeatureExtractor.ImuFeatures> {

    override val channelName = "imu"

    data class ImuOwnerProfile(
        val accMagnitudeMeanRef: Double,
        val accMagnitudeStdRef: Double,
        val energyRef: DoubleArray,     // энергия по 6 осям
        val dominantFreqRef: DoubleArray,
    )

    override fun evaluate(
        features: FeatureExtractor.ImuFeatures,
        windowQuality: SignalPreprocessor.WindowQuality,
    ): ModalityResult {

        // Надёжность: деградирует при вибрации (транспорт) и низкой заполненности.
        // Прокси-сигнал вибрации: высокая std результирующего ускорения при низком fill.
        val vibrationPenalty = when {
            features.accMagnitudeStd > 3.0 -> 0.35   // сильная вибрация (транспорт)
            features.accMagnitudeStd > 1.5 -> 0.15   // умеренное движение
            else -> 0.0
        }
        val reliability = (windowQuality.score * (1.0 - vibrationPenalty)).coerceIn(0.0, 1.0)

        // Score: при отсутствии профиля возвращаем нейтральное значение
        if (ownerProfile == null) {
            return ModalityResult(score = 0.5, reliability = reliability,
                debugInfo = "no_profile:neutral")
        }

        // Косинусное сходство векторов энергии по осям
        val currentEnergy = doubleArrayOf(
            features.accX.energy, features.accY.energy, features.accZ.energy,
            features.gyroX.energy, features.gyroY.energy, features.gyroZ.energy,
        )
        val energySimilarity = cosineSimilarity(currentEnergy, ownerProfile.energyRef)

        // Близость результирующего ускорения к эталону
        val magDiff = abs(features.accMagnitudeMean - ownerProfile.accMagnitudeMeanRef) /
            (ownerProfile.accMagnitudeMeanRef + 1e-9)
        val magScore = exp(-2.0 * magDiff).coerceIn(0.0, 1.0)

        // Итоговый score: взвешенное среднее
        val score = (0.6 * energySimilarity + 0.4 * magScore).coerceIn(0.0, 1.0)

        return ModalityResult(
            score = score,
            reliability = reliability,
            debugInfo = "energySim=%.3f magScore=%.3f vib=%.2f".format(
                energySimilarity, magScore, vibrationPenalty),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Touch-модель
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Touch / gesture модель.
 *
 * Архитектура в полной реализации:
 *  - свайпы → LSTM (временные зависимости траектории)
 *  - тапы   → GRU (разреженные дискретные события)
 *
 * В прototипе: эвристика на основе Маhalanobis-подобного расстояния
 * от эталонного профиля по ключевым признакам.
 *
 * @param ownerProfile  эталонный профиль (mean ± std по признакам).
 */
class TouchModalityModel(
    private val ownerProfile: TouchOwnerProfile? = null,
) : ModalityModel<FeatureExtractor.TouchFeatures> {

    override val channelName = "touch"

    /**
     * Профиль владельца — статистика признаков по обучающим сессиям.
     * Параметры загружаются из защищённого хранилища Android Keystore.
     */
    data class TouchOwnerProfile(
        val swipeSpeedMean: Double,  val swipeSpeedStd: Double,
        val tapDurMean: Double,      val tapDurStd: Double,
        val pressureMean: Double,    val pressureStd: Double,
        val interEventMean: Double,  val interEventStd: Double,
    )

    override fun evaluate(
        features: FeatureExtractor.TouchFeatures,
        windowQuality: SignalPreprocessor.WindowQuality,
    ): ModalityResult {
        // Надёжность: снижается при малом числе событий в окне
        val eventCount = features.tapCount + features.swipeCount
        val coveragePenalty = when {
            eventCount < 3  -> 0.4
            eventCount < 8  -> 0.15
            else            -> 0.0
        }
        val reliability = (windowQuality.score * (1.0 - coveragePenalty)).coerceIn(0.0, 1.0)

        if (ownerProfile == null) {
            return ModalityResult(score = 0.5, reliability = reliability,
                debugInfo = "no_profile:neutral")
        }

        // z-score отклонение каждого признака от эталона
        fun zDist(v: Double, mean: Double, std: Double): Double {
            if (std < 1e-9) return if (abs(v - mean) < 1e-9) 0.0 else 3.0
            return abs(v - mean) / std
        }

        val zScores = listOf(
            zDist(features.swipeSpeedMean, ownerProfile.swipeSpeedMean, ownerProfile.swipeSpeedStd),
            zDist(features.tapDurationMean, ownerProfile.tapDurMean, ownerProfile.tapDurStd),
            zDist(features.pressureMean, ownerProfile.pressureMean, ownerProfile.pressureStd),
            zDist(features.interEventDelayMean, ownerProfile.interEventMean, ownerProfile.interEventStd),
        )

        // Среднее z-расстояние → score через убывающую экспоненту
        val meanZ = zScores.average()
        val score = exp(-0.5 * meanZ).coerceIn(0.0, 1.0)

        return ModalityResult(
            score = score,
            reliability = reliability,
            debugInfo = "meanZ=%.3f events=%d cov=%.2f".format(meanZ, eventCount, coveragePenalty),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Контекстная модель
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Контекстная модель — оценивает типичность текущего контекста для владельца.
 *
 * Не является биометрической в строгом смысле, но стабилизирует
 * байесовский слой при деградации сенсорных каналов (см. раздел 2.3).
 *
 * Надёжность контекстного канала почти не зависит от физической активности —
 * это делает его опорным источником при высоких вибрациях.
 */
class ContextModalityModel(
    private val ownerProfile: ContextOwnerProfile? = null,
) : ModalityModel<FeatureExtractor.ContextFeatures> {

    override val channelName = "context"

    /**
     * Профиль владельца — частоты наблюдения каждого контекста.
     * Пример: владелец 80% времени в STATIC, редко в VEHICLE.
     */
    data class ContextOwnerProfile(
        val activityFreq: Map<FeatureExtractor.ActivityContext, Double>,
        val typicalBatteryRange: Pair<Float, Float>,      // (lo, hi) нормальный диапазон
        val usuallyCharging: Boolean,
    )

    override fun evaluate(
        features: FeatureExtractor.ContextFeatures,
        windowQuality: SignalPreprocessor.WindowQuality,
    ): ModalityResult {
        // Надёжность контекста высокая — зависит только от наличия данных API
        val reliability = windowQuality.fillRatio.toDouble().coerceIn(0.5, 1.0)

        if (ownerProfile == null) {
            return ModalityResult(score = 0.5, reliability = reliability,
                debugInfo = "no_profile:neutral")
        }

        // Частота данного контекста у владельца → если типичный — выше score
        val actFreq = ownerProfile.activityFreq[features.activityContext] ?: 0.1
        val actScore = actFreq.coerceIn(0.1, 1.0)

        // Батарея в ожидаемом диапазоне?
        val (lo, hi) = ownerProfile.typicalBatteryRange
        val battScore = if (features.batteryLevel in lo..hi) 1.0 else 0.7

        val score = (0.7 * actScore + 0.3 * battScore).coerceIn(0.0, 1.0)

        return ModalityResult(
            score = score,
            reliability = reliability,
            debugInfo = "actFreq=%.2f battScore=%.2f".format(actFreq, battScore),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Утилиты
// ─────────────────────────────────────────────────────────────────────────────

private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
    require(a.size == b.size)
    val dot = a.zip(b.toList()).sumOf { (x, y) -> x * y }
    val normA = sqrt(a.sumOf { it * it })
    val normB = sqrt(b.sumOf { it * it })
    if (normA < 1e-12 || normB < 1e-12) return 0.5
    return ((dot / (normA * normB) + 1.0) / 2.0).coerceIn(0.0, 1.0) // нормировка в [0,1]
}
