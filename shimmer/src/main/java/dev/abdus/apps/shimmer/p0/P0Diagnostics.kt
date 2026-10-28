package dev.abdus.apps.shimmer.p0

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import dev.abdus.apps.shimmer.BuildConfig
import dev.abdus.apps.shimmer.WallpaperPreferences
import java.util.concurrent.atomic.AtomicInteger

class P0Application : Application() {
    override fun onCreate() {
        super.onCreate()
        P0Diagnostics.initialize(this)
        // P9-STATIC-SHIMMER-01：壁纸服务与界面同在本进程，Application 先于两者创建，
        // 所以这里清零一次即可保证首帧就没有上游特效
        WallpaperPreferences.create(this).retireShimmerEffectsOnce()
        if (Build.VERSION.SDK_INT >= 36 && BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                .detectBlockedBackgroundActivityLaunch().penaltyLog().build())
        }
    }
}

/** Private, bounded diagnostic ring. Callers supply only numeric values and fixed labels. */
object P0Diagnostics {
    const val TAG = "WallpaperP0"
    private val ids = AtomicInteger()
    private lateinit var app: Context
    private var writer: DiagnosticSerialWriter? = null
    val session = System.currentTimeMillis().toString(36)
    val processSession: String get() = session
    private val core = DiagnosticRunCore(session, clock = {
        DiagnosticClockValue(System.currentTimeMillis(), SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())
    })
    val enabled: Boolean get() = core.enabled()
    fun newId(prefix: String): String = "$prefix-$session-${ids.incrementAndGet()}"

    @Synchronized fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val handler = Handler(HandlerThread("P0EvidenceWriter").apply { start() }.looper)
        val prefs = app.getSharedPreferences("p0_diagnostics", Context.MODE_PRIVATE)
        core.restoreHistory(prefs.getString("tail", "").orEmpty(), prefs.getString("runId", null),
            prefs.getBoolean("enabled", false))
        writer = DiagnosticSerialWriter(object : DiagnosticWriteScheduler {
            override fun schedule(task: Runnable, delayMillis: Long) { handler.postDelayed(task, delayMillis) }
            override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
        }, core::storedSnapshot, store = { snapshot ->
            // Only this HandlerThread writes the diagnostic preference file. No lock covers disk I/O.
            prefs.edit().putBoolean("enabled", snapshot.enabled).putString("tail", snapshot.tail)
                .putString("runId", snapshot.token.runId).putLong("epoch", snapshot.token.epoch)
                .putString("processSession", snapshot.processSession).putLong("startedAtWallMs", snapshot.startedAtWallMs)
                .putLong("revision", snapshot.revision).commit()
        }, acknowledgment = { snapshot, success, failure ->
            core.markPersistence(snapshot.token, snapshot.revision, success, failure)
        }, protocolFailure = { token, revision, failure ->
            if (token != null) core.markPersistence(token, revision ?: -1L, false, failure)
            else Log.e(TAG, "DIAGNOSTIC_WRITER_FAILURE run=UNKNOWN type=$failure")
        }, captureRunToken = core::token)
        if (enabled) changed()
    }

    fun setEnabled(value: Boolean) {
        if (value) core.startPreserving() else core.stop()
        changed(immediate = true)
    }

    fun captureToken(): DiagnosticToken = core.token()
    fun isCurrent(token: DiagnosticToken): Boolean = core.isCurrent(token)
    fun resetAndStart(): DiagnosticToken = core.resetAndStart().also { changed(immediate = true) }

    fun registerEngine(state: EngineDiagnosticState) { core.registerEngine(state); if (enabled) changed() }
    fun updateEngineState(state: EngineDiagnosticState, token: DiagnosticToken? = null): Boolean =
        core.updateEngineState(state, token).also { if (it && enabled) changed() }
    fun updateEngineCache(state: EngineDiagnosticState): Boolean = core.updateEngineCache(state)
    fun unregisterEngine(scope: String) { core.unregisterEngine(scope); if (enabled) changed() }

    fun event(scope: String, kind: String, detail: String = "", token: DiagnosticToken? = null) {
        if (core.event(scope, kind, detail, token)) changed()
    }

    fun gesture(scope: String, kind: String) {
        if (!enabled) return
        core.gesture(scope, kind)
        changed()
    }

    private fun changed(immediate: Boolean = false) {
        core.lastEvent()?.let { Log.i(TAG, it) }
        writer?.request(immediate)
    }

    fun beginTouch(scope: String, event: MotionEvent, editHold: Boolean): DiagnosticTouchTicket? {
        if (!enabled) return null
        // Copy on the callback's owner thread. Neither MotionEvent nor pointer buffers escape.
        val pointers = (0 until minOf(event.pointerCount, 32)).map {
            DiagnosticPointer(event.getPointerId(it), event.getX(it), event.getY(it))
        }
        return core.beginTouch(scope, DiagnosticTouchData(event.actionMasked, event.actionIndex,
            event.eventTime, event.downTime, event.pointerCount, pointers, event.historySize, editHold))
            .also { if (it != null) changed() }
    }

    fun touchForwarded(ticket: DiagnosticTouchTicket?, target: String = "P2_GESTURE") {
        if (core.routeTouch(ticket, forwarded = true, reason = target)) changed()
    }
    fun touchIgnored(ticket: DiagnosticTouchTicket?, reason: String) {
        if (core.routeTouch(ticket, forwarded = false, reason = reason)) changed()
    }
    fun command(scope: String, name: String?, x: Int, y: Int, z: Int, resultRequested: Boolean = false) {
        if (!enabled) return
        core.command(scope, name, x, y, z, resultRequested)
        changed()
    }

    /** Compatibility probe; real services use the separate entrance and actual routing methods. */
    fun rawTouch(scope: String, event: MotionEvent) { beginTouch(scope, event, editHold = false) }

    fun screen(context: Context, scope: String, signal: String, visible: Boolean?, token: DiagnosticToken? = null) {
        if (!enabled || token != null && !isCurrent(token)) return
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        event(scope, "SCREEN_SAMPLE", "signal=$signal interactive=${power.isInteractive} " +
            "keyguardLocked=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked} visible=$visible", token)
    }

    private fun identity(): String {
        // P9-DIAG-TARGET-01：读取清单中的实际目标 SDK；初始化前不把配置或运行系统版本冒充实际值。
        val targetSdk = if (::app.isInitialized) app.applicationInfo.targetSdkVersion.toString() else "UNKNOWN"
        return "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) target=$targetSdk"
    }

    fun snapshot(source: String = "UI_REFRESH"): DiagnosticSnapshot = core.snapshot(identity(), source)
    fun summary(): String = snapshot("SUMMARY_CACHE").summary
    fun export(): String = snapshot("EXPORT_METADATA").content
    fun tail(): String = snapshot("TAIL_CACHE").events.takeLast(18).joinToString("\n")
}

/** In-process control only. No exported broadcasts or image URI extras. Main thread only. */
object P0Controls {
    data class EngineControl(val scope: String, val rebuild: (Boolean) -> Unit, val reload: () -> Unit)
    private val engines = linkedMapOf<String, EngineControl>()
    fun add(control: EngineControl) { engines[control.scope] = control }
    fun remove(scope: String) { engines.remove(scope) }
    fun snapshot(): List<EngineControl> = engines.values.toList()
}
