package ru.nyakshoot.continuousauthapp.pipeline

import kotlin.math.*

/**
 * Извлечение числовых признаков из предобработанных окон.
 *
 * Три канала (см. диплом, таблица 3.1):
 *  - IMU (акселерометр + гироскоп): временные и частотные признаки.
 *  - Touch/gesture: кинематические характеристики касаний.
 *  - Контекст Android API: категориальные и числовые признаки среды.
 */
object FeatureExtractor {

    // ══════════════════════════════════════════════════════════════════════════
    // IMU-признаки
    // ══════════════════════════════════════════════════════════════════════════

    data class ImuAxisFeatures(
        val mean: Double,
        val std: Double,
        val min: Double,
        val max: Double,
        val range: Double,
        val energy: Double,
        val zeroCrossingRate: Double,
        val meanAbsDev: Double,
        val dominantFreq: Double,
        val spectralEntropy: Double,
        val spectralEnergy: Double,
    )

    data class ImuFeatures(
        val accX: ImuAxisFeatures,
        val accY: ImuAxisFeatures,
        val accZ: ImuAxisFeatures,
        val gyroX: ImuAxisFeatures,
        val gyroY: ImuAxisFeatures,
        val gyroZ: ImuAxisFeatures,
        val accMagnitudeMean: Double,
        val accMagnitudeStd: Double,
    ) {
        fun toVector(): DoubleArray {
            val axes = listOf(accX, accY, accZ, gyroX, gyroY, gyroZ)
            val axisVec = axes.flatMap { f ->
                listOf(f.mean, f.std, f.min, f.max, f.range,
                    f.energy, f.zeroCrossingRate, f.meanAbsDev,
                    f.dominantFreq, f.spectralEntropy, f.spectralEnergy)
            }
            return (axisVec + listOf(accMagnitudeMean, accMagnitudeStd)).toDoubleArray()
        }
    }

    fun extractImuAxisFeatures(window: DoubleArray, sampleRateHz: Int = 50): ImuAxisFeatures {
        require(window.isNotEmpty())
        val mean   = window.average()
        val std    = sqrt(window.map { (it - mean).pow(2) }.average())
        val min    = window.min()
        val max    = window.max()
        val energy = window.map { it * it }.average()
        val mad    = window.map { abs(it - mean) }.average()

        var zc = 0
        for (i in 1 until window.size) {
            if ((window[i] >= 0) != (window[i - 1] >= 0)) zc++
        }
        val zcr = zc.toDouble() / window.size

        val spectrum       = computePowerSpectrum(window)
        val freqResolution = sampleRateHz.toDouble() / window.size
        val dominantBin    = spectrum.indices.maxByOrNull { spectrum[it] } ?: 0
        val dominantFreq   = dominantBin * freqResolution
        val totalEnergy    = spectrum.sum().coerceAtLeast(1e-12)
        val spectralEntropy = spectrum.filter { it > 0 }.sumOf { p ->
            val pn = p / totalEnergy; -pn * ln(pn)
        }

        return ImuAxisFeatures(
            mean = mean, std = std, min = min, max = max,
            range = max - min, energy = energy,
            zeroCrossingRate = zcr, meanAbsDev = mad,
            dominantFreq = dominantFreq,
            spectralEntropy = spectralEntropy,
            spectralEnergy = totalEnergy,
        )
    }

    fun extractImuFeatures(
        accX: DoubleArray, accY: DoubleArray, accZ: DoubleArray,
        gyroX: DoubleArray, gyroY: DoubleArray, gyroZ: DoubleArray,
        sampleRateHz: Int = 50,
    ): ImuFeatures {
        val n = minOf(accX.size, accY.size, accZ.size)
        val magnitude = DoubleArray(n) { i ->
            sqrt(accX[i].pow(2) + accY[i].pow(2) + accZ[i].pow(2))
        }
        val magMean = magnitude.average()
        val magStd  = sqrt(magnitude.map { (it - magMean).pow(2) }.average())

        return ImuFeatures(
            accX  = extractImuAxisFeatures(accX,  sampleRateHz),
            accY  = extractImuAxisFeatures(accY,  sampleRateHz),
            accZ  = extractImuAxisFeatures(accZ,  sampleRateHz),
            gyroX = extractImuAxisFeatures(gyroX, sampleRateHz),
            gyroY = extractImuAxisFeatures(gyroY, sampleRateHz),
            gyroZ = extractImuAxisFeatures(gyroZ, sampleRateHz),
            accMagnitudeMean = magMean,
            accMagnitudeStd  = magStd,
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Touch / gesture признаки
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Одно завершённое касание (ACTION_DOWN → ACTION_UP).
     *
     * @param startX/startY  координаты ACTION_DOWN
     * @param x/y            координаты ACTION_UP
     * @param distance       евклидово расстояние start→end (пиксели)
     * @param durationMs     длительность касания
     * @param isTap          true если distance < 20px
     * @param deltaInterEventMs  пауза с момента предыдущего ACTION_UP, null для первого события
     */
    data class TouchEvent(
        val timestampMs:       Long,
        val x:                 Float,
        val y:                 Float,
        val startX:            Float,
        val startY:            Float,
        val distance:          Float,
        val pressure:          Float,
        val size:              Float,
        val durationMs:        Long,
        val isTap:             Boolean,
        val deltaInterEventMs: Long?,
    )

    data class TouchFeatures(
        val swipeSpeedMean:       Double,   // пикс/мс
        val swipeSpeedStd:        Double,
        val swipeCurvatureMean:   Double,   // distance / max(1, duration) — прокси
        val tapDurationMean:      Double,
        val tapDurationStd:       Double,
        val pressureMean:         Double,
        val pressureStd:          Double,
        val touchSizeMean:        Double,
        val interEventDelayMean:  Double,
        val interEventDelayStd:   Double,
        val tapCount:             Int,
        val swipeCount:           Int,
    ) {
        fun toVector(): DoubleArray = doubleArrayOf(
            swipeSpeedMean, swipeSpeedStd, swipeCurvatureMean,
            tapDurationMean, tapDurationStd,
            pressureMean, pressureStd, touchSizeMean,
            interEventDelayMean, interEventDelayStd,
            tapCount.toDouble(), swipeCount.toDouble(),
        )
    }

    fun extractTouchFeatures(events: List<TouchEvent>): TouchFeatures {
        if (events.isEmpty()) return emptyTouchFeatures()

        val taps   = events.filter { it.isTap }
        val swipes = events.filter { !it.isTap }
        val delays = events.mapNotNull { it.deltaInterEventMs?.toDouble() }

        // Скорость свайпа = пройденное расстояние / длительность (пикс/мс)
        val swipeSpeeds = swipes.map { e ->
            if (e.durationMs > 0) e.distance.toDouble() / e.durationMs else 0.0
        }
        // Кривизна — отношение расстояния к max(длительность, 1) — прокси без траектории
        val swipeCurvatures = swipes.map { e ->
            e.distance.toDouble() / maxOf(e.durationMs.toDouble(), 1.0)
        }

        val tapDurations = taps.map { it.durationMs.toDouble() }
        val pressures    = events.map { it.pressure.toDouble() }
        val sizes        = events.map { it.size.toDouble() }

        return TouchFeatures(
            swipeSpeedMean      = swipeSpeeds.meanOrZero(),
            swipeSpeedStd       = swipeSpeeds.stdOrZero(),
            swipeCurvatureMean  = swipeCurvatures.meanOrZero(),
            tapDurationMean     = tapDurations.meanOrZero(),
            tapDurationStd      = tapDurations.stdOrZero(),
            pressureMean        = pressures.meanOrZero(),
            pressureStd         = pressures.stdOrZero(),
            touchSizeMean       = sizes.meanOrZero(),
            interEventDelayMean = delays.meanOrZero(),
            interEventDelayStd  = delays.stdOrZero(),
            tapCount            = taps.size,
            swipeCount          = swipes.size,
        )
    }

    private fun emptyTouchFeatures() = TouchFeatures(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0,
    )

    // ══════════════════════════════════════════════════════════════════════════
    // Контекстные признаки
    // ══════════════════════════════════════════════════════════════════════════

    enum class ActivityContext { STATIC, WALKING, VEHICLE, MIXED }
    enum class NetworkType     { NONE, WIFI, CELLULAR, OTHER }

    data class ContextFeatures(
        val activityContext:     ActivityContext,
        val isCharging:          Boolean,
        val batteryLevel:        Float,     // 0..1
        val isScreenInteractive: Boolean,
        val networkType:         NetworkType,
        val isLandscape:         Boolean,
    ) {
        fun toVector(): DoubleArray {
            val actVec = DoubleArray(ActivityContext.entries.size).also {
                it[activityContext.ordinal] = 1.0
            }
            val netVec = DoubleArray(NetworkType.entries.size).also {
                it[networkType.ordinal] = 1.0
            }
            val scalar = doubleArrayOf(
                if (isCharging) 1.0 else 0.0,
                batteryLevel.toDouble(),
                if (isScreenInteractive) 1.0 else 0.0,
                if (isLandscape) 1.0 else 0.0,
            )
            return actVec + netVec + scalar
        }
    }

    fun buildContextFeatures(
        scenario:           String,
        isCharging:         Boolean,
        batteryLevel:       Float,
        isScreenInteractive: Boolean,
        networkType:        String,
        isLandscape:        Boolean,
    ): ContextFeatures = ContextFeatures(
        activityContext = when (scenario) {
            "walking"    -> ActivityContext.WALKING
            "in_vehicle" -> ActivityContext.VEHICLE
            "custom"     -> ActivityContext.MIXED
            else         -> ActivityContext.STATIC
        },
        isCharging          = isCharging,
        batteryLevel        = batteryLevel.coerceIn(0f, 100f) / 100f,
        isScreenInteractive = isScreenInteractive,
        networkType = when (networkType) {
            "wifi"     -> NetworkType.WIFI
            "cellular" -> NetworkType.CELLULAR
            "none"     -> NetworkType.NONE
            else       -> NetworkType.OTHER
        },
        isLandscape = isLandscape,
    )

    // ══════════════════════════════════════════════════════════════════════════
    // Утилиты
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Одностороннее степенное ДПФ (периодограмма) методом прямого DFT.
     * Сложность O(N²). Для окон ≤ 150 отсчётов — приемлемо на мобильном CPU.
     */
    private fun computePowerSpectrum(signal: DoubleArray): DoubleArray {
        val n    = signal.size
        val half = n / 2 + 1
        val power = DoubleArray(half)
        for (k in 0 until half) {
            var re = 0.0; var im = 0.0
            for (t in 0 until n) {
                val angle = 2.0 * PI * k * t / n
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            power[k] = (re * re + im * im) / n
        }
        return power
    }

    private fun List<Double>.meanOrZero() = if (isEmpty()) 0.0 else average()
    private fun List<Double>.stdOrZero(): Double {
        if (size < 2) return 0.0
        val m = average()
        return sqrt(map { (it - m).pow(2) }.average())
    }

    private operator fun DoubleArray.plus(other: DoubleArray): DoubleArray {
        val result = DoubleArray(size + other.size)
        copyInto(result)
        other.copyInto(result, size)
        return result
    }
}