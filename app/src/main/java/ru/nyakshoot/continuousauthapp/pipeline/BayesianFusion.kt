package ru.nyakshoot.continuousauthapp.pipeline

import kotlin.math.*

/**
 * Байесовский слой слияния модальностей и движок принятия решений.
 *
 * Реализует формулу из раздела 2.4 диплома:
 *   P(U_t = 1 | S_t, C_t, R_t) ∝ P(U_t) · P(C_t | U_t) · ∏ P(s_i,t | U_t, C_t, r_i,t)
 *
 * Вычисления ведутся в log-пространстве во избежание численного обнуления.
 *
 * Трёхзонная политика принятия решений (раздел 2.5):
 *   p ≥ θ_high  → ACCEPT    (прозрачный допуск)
 *   θ_low < p   → ACCUMULATE (накопление окон)
 *   p ≤ θ_low   → STEP_UP   (запрос повторной проверки)
 */

// ─────────────────────────────────────────────────────────────────────────────
// Байесовский слой
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Конфигурация байесовского слоя.
 *
 * @param priorOwner  априорная вероятность того, что устройством пользуется владелец.
 *                    0.95 по умолчанию — посторонний доступ редкое событие.
 * @param contextTable таблица P(context | owner) и P(context | impostor).
 *                    Ключ: ActivityContext, значение: Pair(pOwner, pImpostor).
 */
data class BayesianFusionConfig(
    val priorOwner: Double = 0.95,
    val contextTable: Map<FeatureExtractor.ActivityContext, Pair<Double, Double>> = defaultContextTable(),
    val thetaHigh: Double = 0.85,
    val thetaLow: Double = 0.35,
    val accumulationWindowCount: Int = 3,   // сколько окон накапливать в зоне неопределённости
)

private fun defaultContextTable() = mapOf(
    FeatureExtractor.ActivityContext.STATIC  to Pair(0.90, 0.50),
    FeatureExtractor.ActivityContext.WALKING to Pair(0.80, 0.40),
    FeatureExtractor.ActivityContext.VEHICLE to Pair(0.65, 0.30),
    FeatureExtractor.ActivityContext.MIXED   to Pair(0.75, 0.38),
)

/** Входные данные одного окна для байесовского слоя. */
data class FusionInput(
    val imuResult: ModalityResult,
    val touchResult: ModalityResult,
    val contextResult: ModalityResult,
    val activityContext: FeatureExtractor.ActivityContext,
    val windowIndex: Long,              // монотонно возрастающий счётчик окон
)

/** Выход байесовского слоя — вероятность подлинности и детали. */
data class FusionOutput(
    val posteriorOwner: Double,         // P(U=1 | данные), ∈ [0, 1]
    val logPosteriorOwner: Double,      // в log-пространстве
    val weightedScores: Map<String, Double>,   // вклад каждого канала
    val effectiveWeights: Map<String, Double>, // нормированные веса (reliability)
)

class BayesianFusion(private val config: BayesianFusionConfig = BayesianFusionConfig()) {

    /**
     * Вычисляет P(U_t = 1 | S_t, C_t, R_t) в log-пространстве.
     *
     * Алгоритм:
     *  1. log-prior: ln P(U) и ln P(¬U)
     *  2. Контекстная правдоподобность: ln P(C_t | U)
     *  3. Для каждого канала i:
     *       эффективный вес w_i = r_i,t (надёжность)
     *       ln P(s_i | U, r_i) ≈ w_i · ln[N(s_i; μ_owner, σ²_i)]
     *     (Гауссово приближение: при r_i → 0 σ²_i → ∞, вклад → 0)
     *  4. Нормировка: softmax двух гипотез → posterior
     */
    fun compute(input: FusionInput): FusionOutput {
        val prior1 = config.priorOwner
        val prior0 = 1.0 - prior1

        val logPrior1 = ln(prior1)
        val logPrior0 = ln(prior0)

        // Контекстные правдоподобности
        val (pCtxOwner, pCtxImpostor) = config.contextTable[input.activityContext]
            ?: Pair(0.75, 0.40)
        val logCtx1 = ln(pCtxOwner.coerceAtLeast(1e-9))
        val logCtx0 = ln(pCtxImpostor.coerceAtLeast(1e-9))

        // Канальные вклады в log-пространстве
        val modalities = listOf(input.imuResult, input.touchResult, input.contextResult)
        val names = listOf("imu", "touch", "context")

        var logLikelihood1 = 0.0
        var logLikelihood0 = 0.0
        val weightedScores = mutableMapOf<String, Double>()
        val effectiveWeights = mutableMapOf<String, Double>()

        for ((i, result) in modalities.withIndex()) {
            val r = result.reliability           // надёжность = эффективный вес
            val s = result.score

            // При надёжности r: σ² = (1-r)/(r+ε) + min_noise
            // Чем ниже r — тем шире распределение → меньше вклад
            val sigma2 = (1.0 - r) / (r + 0.05) + 0.01
            val sigma2Impostor = sigma2 * 1.5   // имитатор менее предсказуем

            // log P(s | U=1): Гауссово с μ=0.85 (ожидаемый score владельца)
            val logP1 = logGaussian(s, mu = 0.85, sigma2 = sigma2)
            // log P(s | U=0): Гауссово с μ=0.35 (ожидаемый score имитатора)
            val logP0 = logGaussian(s, mu = 0.35, sigma2 = sigma2Impostor)

            logLikelihood1 += r * logP1
            logLikelihood0 += r * logP0

            weightedScores[names[i]] = r * s
            effectiveWeights[names[i]] = r
        }

        val logPost1 = logPrior1 + logCtx1 + logLikelihood1
        val logPost0 = logPrior0 + logCtx0 + logLikelihood0

        // Стабильный softmax: вычитаем максимум
        val maxLog = maxOf(logPost1, logPost0)
        val exp1 = exp(logPost1 - maxLog)
        val exp0 = exp(logPost0 - maxLog)
        val posterior = (exp1 / (exp1 + exp0)).coerceIn(0.0, 1.0)

        return FusionOutput(
            posteriorOwner = posterior,
            logPosteriorOwner = logPost1,
            weightedScores = weightedScores,
            effectiveWeights = effectiveWeights,
        )
    }

    private fun logGaussian(x: Double, mu: Double, sigma2: Double): Double {
        val s2 = sigma2.coerceAtLeast(1e-6)
        return -0.5 * ln(2.0 * PI * s2) - (x - mu).pow(2) / (2.0 * s2)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Decision Engine
// ─────────────────────────────────────────────────────────────────────────────

/** Решение системы за одно окно наблюдений. */
enum class AuthDecision {
    ACCEPT,       // p ≥ θ_high — пользователь подтверждён, сессия продолжается
    ACCUMULATE,   // θ_low < p < θ_high — накапливаем ещё окна
    STEP_UP,      // p ≤ θ_low — запрос PIN / биометрии
}

/** Полный журнальный результат одного цикла обработки окна. */
data class DecisionRecord(
    val windowIndex: Long,
    val timestampMs: Long,
    val posteriorOwner: Double,
    val decision: AuthDecision,
    val fusionOutput: FusionOutput,
    val accumulationCount: Int,     // сколько ACCUMULATE-окон подряд
    val imuDebug: String,
    val touchDebug: String,
    val contextDebug: String,
)

/**
 * Движок принятия решений.
 *
 * Поддерживает состояние накопления: при нескольких подряд ACCUMULATE-окнах
 * переходит в STEP_UP, не ожидая дальнейшего снижения вероятности.
 */
class DecisionEngine(private val config: BayesianFusionConfig = BayesianFusionConfig()) {

    private var accumulationCount = 0

    /**
     * Принимает решение на основе posterior из байесовского слоя.
     * Обновляет внутреннее состояние накопления.
     */
    fun decide(
        fusionOutput: FusionOutput,
        fusionInput: FusionInput,
    ): DecisionRecord {
        val p = fusionOutput.posteriorOwner

        val rawDecision = when {
            p >= config.thetaHigh -> AuthDecision.ACCEPT
            p <= config.thetaLow  -> AuthDecision.STEP_UP
            else                  -> AuthDecision.ACCUMULATE
        }

        // При накоплении: если превышен лимит окон → STEP_UP
        val decision = when {
            rawDecision == AuthDecision.ACCEPT -> {
                accumulationCount = 0   // сброс счётчика при уверенном допуске
                AuthDecision.ACCEPT
            }
            rawDecision == AuthDecision.ACCUMULATE -> {
                accumulationCount++
                if (accumulationCount >= config.accumulationWindowCount) {
                    accumulationCount = 0
                    AuthDecision.STEP_UP    // слишком долго в зоне неопределённости
                } else {
                    AuthDecision.ACCUMULATE
                }
            }
            else -> {
                accumulationCount = 0
                AuthDecision.STEP_UP
            }
        }

        return DecisionRecord(
            windowIndex = fusionInput.windowIndex,
            timestampMs = System.currentTimeMillis(),
            posteriorOwner = p,
            decision = decision,
            fusionOutput = fusionOutput,
            accumulationCount = accumulationCount,
            imuDebug = fusionInput.imuResult.debugInfo,
            touchDebug = fusionInput.touchResult.debugInfo,
            contextDebug = fusionInput.contextResult.debugInfo,
        )
    }

    /** Сброс состояния при новой сессии или после step-up. */
    fun reset() {
        accumulationCount = 0
    }
}
