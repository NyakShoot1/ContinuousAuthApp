package ru.nyakshoot.continuousauthapp.pipeline

import kotlin.math.sqrt

/**
 * Предобработка сырых сенсорных данных перед извлечением признаков.
 *
 * Этапы (см. диплом, раздел 3.4):
 *  1. Low-pass фильтр Баттерворта 2-го порядка (срез 20 Гц) — подавление ВЧ-шума IMU.
 *  2. Z-score нормализация — приведение к единому масштабу.
 *  3. Скользящее окно с перекрытием — сегментация непрерывного потока.
 *  4. Оценка качества окна — заполненность + IQR-выбросы → WindowQuality.
 */
class SignalPreprocessor(
    private val sampleRateHz: Int = 50,
    private val windowSizeSec: Float = 2f,
    private val overlapFraction: Float = 0.5f,
    private val cutoffHz: Float = 20f,
) {

    // ── Фильтр Баттерворта 2-го порядка ──────────────────────────────────────

    /**
     * Коэффициенты low-pass фильтра Баттерворта 2-го порядка.
     * Рассчитаны по формуле биlinear-transform для заданных sampleRate / cutoff.
     */
    private val butterCoeffs: ButterworthCoeffs by lazy {
        computeButterworthCoeffs(cutoffHz, sampleRateHz.toFloat())
    }

    /**
     * Применяет low-pass фильтр Баттерворта к одной оси сигнала.
     * Использует прямую форму II (Direct Form II Transposed) для численной устойчивости.
     *
     * @param signal  сырой сигнал по одной оси (x, y или z)
     * @return        отфильтрованный сигнал той же длины
     */
    fun lowPassFilter(signal: DoubleArray): DoubleArray {
        val (b, a) = butterCoeffs
        val out = DoubleArray(signal.size)
        var w1 = 0.0
        var w2 = 0.0
        for (n in signal.indices) {
            val w0 = signal[n] - a[1] * w1 - a[2] * w2
            out[n] = b[0] * w0 + b[1] * w1 + b[2] * w2
            w2 = w1
            w1 = w0
        }
        return out
    }

    // ── Z-score нормализация ──────────────────────────────────────────────────

    /**
     * Z-score нормализация: (x − μ) / σ.
     * Если σ < eps — возвращает нулевой массив (сигнал константный / пустой).
     */
    fun zScoreNormalize(signal: DoubleArray): DoubleArray {
        if (signal.isEmpty()) return signal
        val mean = signal.average()
        val std = sqrt(signal.map { (it - mean) * (it - mean) }.average())
        if (std < 1e-9) return DoubleArray(signal.size)
        return DoubleArray(signal.size) { i -> (signal[i] - mean) / std }
    }

    // ── Скользящее окно ───────────────────────────────────────────────────────

    data class Window(
        val samples: DoubleArray,
        val startIdx: Int,
        val endIdx: Int,
    )

    /**
     * Нарезает сигнал скользящим окном с перекрытием.
     *
     * @param signal  нормализованный сигнал
     * @return        список окон фиксированного размера
     */
    fun slidingWindows(signal: DoubleArray): List<Window> {
        val windowSize = (sampleRateHz * windowSizeSec).toInt()
        val step = (windowSize * (1f - overlapFraction)).toInt().coerceAtLeast(1)
        val windows = mutableListOf<Window>()
        var start = 0
        while (start + windowSize <= signal.size) {
            windows += Window(
                samples = signal.copyOfRange(start, start + windowSize),
                startIdx = start,
                endIdx = start + windowSize - 1,
            )
            start += step
        }
        return windows
    }

    // ── Оценка качества окна ──────────────────────────────────────────────────

    /**
     * Качество одного окна: используется как входной параметр для reliability-модуля.
     *
     * @param fillRatio    доля ненулевых / не-NaN отсчётов (0..1)
     * @param outlierRatio доля выбросов по IQR (0..1)
     * @param score        итоговое качество (0..1), высокое = хорошие данные
     */
    data class WindowQuality(
        val fillRatio: Float,
        val outlierRatio: Float,
        val score: Float,
    )

    /**
     * Оценивает качество окна.
     *
     * Алгоритм:
     *  - fillRatio  = доля отсчётов, не равных NaN и не равных 0.0 подряд (признак пропуска).
     *  - outlierRatio = доля отсчётов за пределами [Q1 − 1.5·IQR, Q3 + 1.5·IQR].
     *  - score = fillRatio × (1 − outlierRatio), зажатое в [0, 1].
     */
    fun assessQuality(window: DoubleArray): WindowQuality {
        if (window.isEmpty()) return WindowQuality(0f, 1f, 0f)

        // Заполненность — считаем не-нулевые подряд-блоки как пропуски
        val fillRatio = window.count { it != 0.0 }.toFloat() / window.size

        // IQR-выбросы
        val sorted = window.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[sorted.size * 3 / 4]
        val iqr = q3 - q1
        val lo = q1 - 1.5 * iqr
        val hi = q3 + 1.5 * iqr
        val outlierRatio = window.count { it < lo || it > hi }.toFloat() / window.size

        val score = (fillRatio * (1f - outlierRatio)).coerceIn(0f, 1f)
        return WindowQuality(fillRatio, outlierRatio, score)
    }

    // ── Полный пайплайн для одной оси IMU ────────────────────────────────────

    /**
     * Прогоняет сырую ось IMU через полный пайплайн предобработки:
     * фильтрация → нормализация → нарезка на окна.
     */
    fun preprocessImuAxis(raw: DoubleArray): List<Pair<Window, WindowQuality>> {
        val filtered = lowPassFilter(raw)
        val normalized = zScoreNormalize(filtered)
        val windows = slidingWindows(normalized)
        return windows.map { w -> w to assessQuality(w.samples) }
    }

    /**
     * Прогоняет touch-временной ряд через нормализацию и нарезку.
     * Фильтр Баттерворта не применяется — touch-события разреженные.
     */
    fun preprocessTouchSeries(raw: DoubleArray): List<Pair<Window, WindowQuality>> {
        val normalized = zScoreNormalize(raw)
        val windows = slidingWindows(normalized)
        return windows.map { w -> w to assessQuality(w.samples) }
    }

    // ── Внутренние вычисления коэффициентов ──────────────────────────────────

    private data class ButterworthCoeffs(val b: DoubleArray, val a: DoubleArray)

    /**
     * Вычисляет коэффициенты low-pass фильтра Баттерворта 2-го порядка
     * методом bilinear transform (предварительное искажение частоты — warping).
     *
     * Формулы:
     *   Ω_c = 2·fs·tan(π·fc/fs)    — предискажённая аналоговая частота среза
     *   k   = Ω_c / (2·fs)
     *   norm = k² + √2·k + 1
     *   b0 = b1/2 = b2 = k² / norm
     *   a1 = 2·(k²−1)/norm,  a2 = (k²−√2·k+1)/norm
     */
    private fun computeButterworthCoeffs(fc: Float, fs: Float): ButterworthCoeffs {
        val sqrt2 = sqrt(2.0)
        val k = Math.tan(Math.PI * fc / fs)
        val norm = k * k + sqrt2 * k + 1.0
        val b0 = k * k / norm
        val b1 = 2.0 * b0
        val b2 = b0
        val a1 = 2.0 * (k * k - 1.0) / norm
        val a2 = (k * k - sqrt2 * k + 1.0) / norm
        return ButterworthCoeffs(
            b = doubleArrayOf(b0, b1, b2),
            a = doubleArrayOf(1.0, a1, a2),
        )
    }
}
