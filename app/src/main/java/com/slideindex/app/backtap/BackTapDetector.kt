package com.slideindex.app.backtap

/**
 * Portions derived from EdgeGesture (https://github.com/evilgodxu/EdgeGesture)
 * Licensed under GPL-3.0. Modified for com.slideindex.app.
 */

import android.content.Context
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.slideindex.app.shake.MotionSensorHub
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 背面双击检测器。
 *
 * 敲击检测以加速度计为主；场景感知会结合距离传感器（TYPE_PROXIMITY）与光线传感器
 * （TYPE_LIGHT）判断口袋/遮挡状态，无权限要求。若设备缺少这些传感器，则回退到纯
 * 加速度计运动特征判断。
 *
 * 新增能力：
 * - 持续振动/晃动过滤：能量长时间高于低阈值时进入抑制状态。
 * - 大波形/饱和过滤：超过饱和阈值的冲击直接拒绝。
 * - 场景感知：根据长期运动能量 + 距离/光线传感器自动区分手持、桌面、运动、口袋，
 *   并动态调整采样率。
 * - 工作模式：始终激活 / 仅黑屏激活 / 仅亮屏激活，按需注册/注销传感器以降低功耗。
 */
class BackTapDetector(
    context: Context,
    private val sensorHub: MotionSensorHub,
    private val onDoubleTap: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld = false

    private val wakeLockRenewRunnable = Runnable {
        synchronized(this) {
            if (!isWakeLockHeld || !isListening || isPaused || isScreenOn || isSuspended) return@Runnable
            wakeLock?.release()
            isWakeLockHeld = false
            acquireWakeLock()
        }
    }

    // 信号处理
    private val hpX = HighPassFilter(CUTOFF_HP_HZ)
    private val hpY = HighPassFilter(CUTOFF_HP_HZ)
    private val hpZ = HighPassFilter(CUTOFF_HP_HZ)
    private val lpMag = LowPassFilter(CUTOFF_LP_HZ)
    private val noiseEstimator = NoiseEstimator(NOISE_EMA_ALPHA)

    // 运行状态
    @Volatile private var isListening = false
    @Volatile private var isRegistered = false

    /**
     * 5Hz 倾角监视器：只判断手机是否平放（桌面/床头的典型姿态），不参与敲击判定。
     *
     * 息屏 + 平放/口袋持续 [SUSPEND_DEBOUNCE_MS] 后停掉 500Hz 采样并释放唤醒锁；
     * 拿起（倾角变化）或离开口袋时立即恢复。这样"始终触发"在手持场景下不变，
     * 但整夜放桌上不再常驻采样。
     */
    private val tiltWatcher = Any()
    @Volatile private var isFlat = false
    @Volatile private var isSuspended = false
    @Volatile private var isPocketNow = false
    private val suspendRunnable = Runnable { suspendForContext() }
    private var activationMode = BackTapMode.SCREEN_ON
    @Volatile private var isScreenOn = false
    @Volatile private var isPaused = false

    // 可调参数
    private var baseThreshold = DEFAULT_THRESHOLD
    private var minPulseNs = MIN_PULSE_NS
    private var maxPulseNs = MAX_PULSE_NS
    private var lockoutNs = LOCKOUT_NS
    private var minGapNs = MIN_GAP_NS
    private var maxGapNs = DEFAULT_MAX_GAP_NS

    // 敲击状态机
    private enum class State { IDLE, IN_PULSE, LOCKOUT }
    private var state = State.IDLE
    private var pulseStartNs = 0L
    private var lastPulseEndNs = 0L
    private var lastTapNs = 0L
    private var pulsePeakEnergy = 0f

    // 抗晃动 / 大波形
    private var lastQuietNs = 0L
    private var sustainedMotion = false

    // 环境传感器状态
    private var isProximityNear = false
    private var lastLightLux = 0f

    // 灵敏度 1-10，越高阈值越低（越灵敏）
    fun setSensitivity(value: Int) {
        val v = value.coerceIn(1, 10)
        baseThreshold = (MAX_THRESHOLD * exp(-v * SENSITIVITY_EXP_FACTOR)).toFloat()
    }

    // 双击间隔 1-10（对应 310ms ~ 850ms），越高允许的双击间隔越长
    fun setRange(value: Int) {
        val v = value.coerceIn(1, 10)
        maxGapNs = MIN_MAX_GAP_NS + v * RANGE_STEP_NS
    }

    // 工作模式：始终 / 仅黑屏 / 仅亮屏
    fun setMode(mode: BackTapMode) {
        synchronized(this) {
            activationMode = mode
            applyActivationState()
        }
    }

    // 屏幕状态，由服务在收到亮灭屏广播时更新
    fun setScreenOn(isOn: Boolean) {
        synchronized(this) {
            val wasOn = isScreenOn
            isScreenOn = isOn
            if (isOn) {
                // 亮屏即退出挂起：用户可能正拿着手机敲背面
                cancelPendingSuspend()
                isSuspended = false
            }
            applyActivationState()
            // 部分设备息屏后会挂起非唤醒传感器，重新注册以恢复采样
            if (isListening && isRegistered && !isOn && wasOn) {
                sensorHub.unregister(this)
                registerSensors()
            }
            if (!isOn && wasOn) {
                // 息屏瞬间若已是平放/口袋姿态，立刻开始待停计时
                maybeSuspendForContext()
            }
        }
    }

    fun start() {
        start(5, 5)
    }

    fun start(sensitivity: Int, range: Int) {
        synchronized(this) {
            if (isListening || !sensorHub.hasAccelerometer) return
            setSensitivity(sensitivity)
            setRange(range)
            reset()
            isListening = true
            registerTiltWatcher()
            applyActivationState()
        }
    }

    fun stop() {
        synchronized(this) {
            if (!isListening) return
            cancelPendingSuspend()
            if (isRegistered) sensorHub.unregister(this)
            sensorHub.unregister(tiltWatcher)
            isSuspended = false
            isFlat = false
            isPocketNow = false
            releaseWakeLock()
            isListening = false
            isRegistered = false
            isPaused = false
            reset()
        }
    }

    // 由外部管理器在充电等场景调用，临时注销传感器；恢复后调用 resume()
    fun pause() {
        synchronized(this) {
            if (isPaused) return
            isPaused = true
            applyActivationState()
        }
    }

    fun resume() {
        synchronized(this) {
            if (!isPaused) return
            isPaused = false
            applyActivationState()
        }
    }

    private fun registerSensors() {
        sensorHub.register(
            MotionSensorHub.Kind.ACCELEROMETER,
            this,
            SensorManager.SENSOR_DELAY_FASTEST,
        ) { processAccelerometer(it) }
        if (sensorHub.hasProximity) {
            sensorHub.register(
                MotionSensorHub.Kind.PROXIMITY,
                this,
                SensorManager.SENSOR_DELAY_NORMAL,
            ) { processProximity(it) }
        }
        if (sensorHub.hasLight) {
            sensorHub.register(
                MotionSensorHub.Kind.LIGHT,
                this,
                SensorManager.SENSOR_DELAY_NORMAL,
            ) { processLight(it) }
        }
    }

    private fun applyActivationState() {
        if (!isListening) return
        if (isPaused) {
            if (isRegistered) {
                sensorHub.unregister(this)
                isRegistered = false
                releaseWakeLock()
                reset()
            }
            isSuspended = false
            cancelPendingSuspend()
            return
        }
        val shouldRegister = when (activationMode) {
            BackTapMode.ALWAYS -> true
            BackTapMode.SCREEN_OFF -> !isScreenOn
            BackTapMode.SCREEN_ON -> isScreenOn
        }
        if (!shouldRegister) {
            if (isRegistered) {
                sensorHub.unregister(this)
                isRegistered = false
                releaseWakeLock()
                reset()
            }
            isSuspended = false
            cancelPendingSuspend()
            return
        }
        // 息屏且已判定为"平放/口袋"：保持挂起，只留 5Hz 倾角监视
        if (isSuspended) {
            releaseWakeLock()
            return
        }
        if (!isScreenOn) acquireWakeLock() else releaseWakeLock()
        if (!isRegistered) {
            registerSensors()
            isRegistered = true
        }
    }

    private fun registerTiltWatcher() {
        sensorHub.register(
            MotionSensorHub.Kind.ACCELEROMETER,
            tiltWatcher,
            TILT_WATCH_DELAY_US,
        ) { processTilt(it) }
    }

    /**
     * 5Hz 倾角监视：平放（含正面朝下）进入待停状态，拿起则立刻恢复高频检测。
     * 只用于省电门控，不参与敲击判定。
     */
    private fun processTilt(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val norm = sqrt(x * x + y * y + z * z)
        if (norm < 0.1f) return
        val incline = Math.toDegrees(acos((z / norm).toDouble().coerceIn(-1.0, 1.0)))
        val flat = incline < FLAT_INCLINE_DEG || incline > 180.0 - FLAT_INCLINE_DEG
        if (flat == isFlat) return
        isFlat = flat
        if (flat) maybeSuspendForContext() else resumeFromContext()
    }

    private fun updatePocketState() {
        val pocket = isProximityNear &&
            (!sensorHub.hasLight || lastLightLux < POCKET_LIGHT_LUX_THRESHOLD)
        if (pocket == isPocketNow) return
        isPocketNow = pocket
        if (pocket) maybeSuspendForContext() else resumeFromContext()
    }

    private fun maybeSuspendForContext() {
        if (isScreenOn || isSuspended || !isListening || isPaused) return
        if (!isFlat && !isPocketNow) return
        handler.removeCallbacks(suspendRunnable)
        handler.postDelayed(suspendRunnable, SUSPEND_DEBOUNCE_MS)
    }

    private fun cancelPendingSuspend() {
        handler.removeCallbacks(suspendRunnable)
    }

    private fun suspendForContext() {
        if (!isListening || isPaused || isScreenOn) return
        if (!isFlat && !isPocketNow) return
        if (isRegistered) {
            sensorHub.unregister(this)
            isRegistered = false
            reset()
        }
        releaseWakeLock()
        isSuspended = true
    }

    private fun resumeFromContext() {
        if (!isSuspended) {
            cancelPendingSuspend()
            return
        }
        if (!isScreenOn && isFlat && isPocketNow) return
        isSuspended = false
        cancelPendingSuspend()
        applyActivationState()
    }

    private fun reset() {
        state = State.IDLE
        pulseStartNs = 0L
        lastPulseEndNs = 0L
        lastTapNs = 0L
        pulsePeakEnergy = 0f
        lastQuietNs = 0L
        sustainedMotion = false
        isProximityNear = false
        lastLightLux = 0f
        hpX.reset()
        hpY.reset()
        hpZ.reset()
        lpMag.reset()
        noiseEstimator.reset()
    }

    private fun ensureWakeLock() {
        if (wakeLock != null) return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlideIndex:BackTap")
    }

    private fun acquireWakeLock() {
        ensureWakeLock()
        if (!isWakeLockHeld) {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            isWakeLockHeld = true
            handler.removeCallbacks(wakeLockRenewRunnable)
            handler.postDelayed(wakeLockRenewRunnable, WAKE_LOCK_RENEW_MS)
        }
    }

    private fun releaseWakeLock() {
        if (isWakeLockHeld) {
            handler.removeCallbacks(wakeLockRenewRunnable)
            wakeLock?.release()
            isWakeLockHeld = false
        }
    }

    @Synchronized
    private fun processAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val t = event.timestamp

        if (!hpX.isInitialized) {
            initFilters(x, y, z, t)
            return
        }

        // 1. 提取与方向无关的瞬时冲击能量
        val xh = hpX.update(x, t)
        val yh = hpY.update(y, t)
        val zh = hpZ.update(z, t)
        val mag = sqrt(xh * xh + yh * yh + zh * zh)
        val energy = lpMag.update(mag, t)

        // 2. 自适应阈值：只在空闲且未触发时更新噪声基底
        val noiseFloor = noiseEstimator.currentEstimate
        val threshold = baseThreshold + ADAPTIVE_FACTOR * noiseFloor
        noiseEstimator.update(energy, state == State.IDLE && energy < threshold)

        // 3. 持续晃动 / 连续波形检测
        updateSustainedMotion(energy, t)

        // 4. 口袋判定（只用于屏蔽敲击；采样率不再随场景切换，避免反复重注册传感器）
        val isPocket = isProximityNear && (!sensorHub.hasLight || lastLightLux < POCKET_LIGHT_LUX_THRESHOLD)

        // 5. 口袋/遮挡状态立即屏蔽敲击，避免误触发
        if (isPocket) {
            if (state == State.IN_PULSE) {
                lastPulseEndNs = t
                state = State.LOCKOUT
            }
            return
        }

        // 6. 不应期结束则回到空闲
        if (state == State.LOCKOUT && t - lastPulseEndNs > lockoutNs) {
            state = State.IDLE
        }

        // 7. 大波形 / 饱和过滤：连续振动期间直接忽略脉冲
        if (sustainedMotion || energy > SATURATION_THRESHOLD) {
            if (state == State.IN_PULSE) {
                lastPulseEndNs = t
                state = State.LOCKOUT
            }
            return
        }

        // 7. 单敲击状态机
        when (state) {
            State.IDLE -> {
                if (energy > threshold) {
                    state = State.IN_PULSE
                    pulseStartNs = t
                    pulsePeakEnergy = energy
                }
            }
            State.IN_PULSE -> {
                pulsePeakEnergy = max(pulsePeakEnergy, energy)
                val duration = t - pulseStartNs
                val released = energy < threshold * RELEASE_RATIO
                if (released || duration > maxPulseNs) {
                    lastPulseEndNs = t
                    state = State.LOCKOUT
                    if (released && duration in minPulseNs..maxPulseNs
                        && pulsePeakEnergy >= threshold * PEAK_RATIO
                    ) {
                        registerTap(t)
                    }
                }
            }
            State.LOCKOUT -> { /* 等待不应期结束 */ }
        }
    }

    @Synchronized
    private fun processProximity(event: SensorEvent) {
        // 多数设备的距离传感器只报告 0（近）和 maximumRange（远），按半阈值判断即可兼容
        val maxRange = event.sensor.maximumRange
        isProximityNear = event.values[0] < maxRange * 0.75f
        updatePocketState()
    }

    @Synchronized
    private fun processLight(event: SensorEvent) {
        lastLightLux = event.values[0]
        updatePocketState()
    }

    private fun initFilters(x: Float, y: Float, z: Float, t: Long) {
        hpX.init(x, t)
        hpY.init(y, t)
        hpZ.init(z, t)
        lpMag.init(0f, t)
        noiseEstimator.init(0f)
        lastQuietNs = t
    }

    private fun updateSustainedMotion(energy: Float, t: Long) {
        if (energy < SUSTAINED_THRESHOLD) {
            lastQuietNs = t
            sustainedMotion = false
        } else if (t - lastQuietNs > SUSTAINED_MOTION_NS) {
            sustainedMotion = true
        }
    }

    private fun registerTap(t: Long) {
        val gap = t - lastTapNs
        when {
            lastTapNs != 0L && gap in minGapNs..maxGapNs -> {
                handler.post { onDoubleTap() }
                lastTapNs = 0L
            }
            gap >= minGapNs || lastTapNs == 0L -> {
                lastTapNs = t
            }
            // gap < minGapNs：视为同一次物理敲击的残余，忽略
        }
    }

    private class HighPassFilter(private val cutoffHz: Float) {
        private var lastInput = 0f
        private var lastOutput = 0f
        private var lastTime = 0L
        private var initialized = false

        val isInitialized get() = initialized

        fun init(value: Float, t: Long) {
            lastInput = value
            lastOutput = 0f
            lastTime = t
            initialized = true
        }

        fun reset() {
            initialized = false
            lastTime = 0L
            lastInput = 0f
            lastOutput = 0f
        }

        fun update(value: Float, t: Long): Float {
            val dt = ((t - lastTime) / 1_000_000_000f).coerceIn(0.0005f, 0.1f)
            lastTime = t
            val rc = 1f / (2f * PI.toFloat() * cutoffHz)
            val alpha = rc / (rc + dt)
            lastOutput = alpha * (lastOutput + value - lastInput)
            lastInput = value
            return lastOutput
        }
    }

    private class LowPassFilter(private val cutoffHz: Float) {
        private var lastOutput = 0f
        private var lastTime = 0L
        private var initialized = false

        val isInitialized get() = initialized

        fun init(value: Float, t: Long) {
            lastOutput = value
            lastTime = t
            initialized = true
        }

        fun reset() {
            initialized = false
            lastTime = 0L
            lastOutput = 0f
        }

        fun update(value: Float, t: Long): Float {
            val dt = ((t - lastTime) / 1_000_000_000f).coerceIn(0.0005f, 0.1f)
            lastTime = t
            val rc = 1f / (2f * PI.toFloat() * cutoffHz)
            val alpha = dt / (rc + dt)
            lastOutput = alpha * value + (1f - alpha) * lastOutput
            return lastOutput
        }
    }

    private class NoiseEstimator(private val alpha: Float) {
        private var estimate = 0f
        private var initialized = false

        val currentEstimate get() = estimate

        fun init(value: Float) {
            estimate = value
            initialized = true
        }

        fun reset() {
            initialized = false
            estimate = 0f
        }

        fun update(value: Float, adopt: Boolean): Float {
            if (adopt) {
                estimate = if (initialized) {
                    estimate * (1f - alpha) + value * alpha
                } else {
                    value.also { initialized = true }
                }
            }
            return estimate
        }
    }

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = WAKE_LOCK_TIMEOUT_MS - 60_000L

        // 省电门控：平放/口袋持续这么久后停掉高频检测
        private const val TILT_WATCH_DELAY_US = 200_000 // 5Hz，只算倾角
        private const val FLAT_INCLINE_DEG = 25.0
        private const val SUSPEND_DEBOUNCE_MS = 60_000L

        // 滤波器截止频率
        private const val CUTOFF_HP_HZ = 5f
        private const val CUTOFF_LP_HZ = 80f

        // 灵敏度曲线：值越大阈值越低
        private const val MAX_THRESHOLD = 16f // m/s^2
        private const val SENSITIVITY_EXP_FACTOR = 0.18

        // 阈值自适应
        private const val ADAPTIVE_FACTOR = 3f
        private const val NOISE_EMA_ALPHA = 0.02f

        // 脉冲检测
        private const val RELEASE_RATIO = 0.55f
        private const val PEAK_RATIO = 1.3f
        private const val MIN_PULSE_NS = 8_000_000L
        private const val MAX_PULSE_NS = 120_000_000L
        private const val LOCKOUT_NS = 100_000_000L

        // 持续振动/晃动过滤
        private const val SUSTAINED_THRESHOLD = 1.2f // m/s^2
        private const val SUSTAINED_MOTION_NS = 250_000_000L

        // 大波形/饱和过滤
        private const val SATURATION_THRESHOLD = 40f // m/s^2

        // 双击时序
        private const val MIN_GAP_NS = 100_000_000L
        private const val MIN_MAX_GAP_NS = 250_000_000L
        private const val RANGE_STEP_NS = 60_000_000L

        // 口袋判断：距离传感器被遮挡且光线较暗
        private const val POCKET_LIGHT_LUX_THRESHOLD = 10f

        private const val DEFAULT_THRESHOLD = 6f
        private const val DEFAULT_MAX_GAP_NS = 550_000_000L
    }
}
