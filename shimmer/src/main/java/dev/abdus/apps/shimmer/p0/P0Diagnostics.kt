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
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class P0Application : Application() {
    override fun onCreate() {
        super.onCreate()
        P0Diagnostics.initialize(this)
        if (Build.VERSION.SDK_INT >= 36 && BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                .detectBlockedBackgroundActivityLaunch().penaltyLog().build())
        }
    }
}

/** Private, bounded diagnostic ring. Callers supply only numeric values and fixed labels. */
object P0Diagnostics {
    const val TAG = "WallpaperP0"
    private const val MAX_EVENTS = 1600
    private const val MAX_LINE = 640
    private val lines = ArrayDeque<String>()
    private val counts = linkedMapOf<String, Int>()
    private val ids = AtomicInteger()
    private lateinit var app: Context
    private lateinit var writer: Handler
    @Volatile var enabled = false
        private set
    private var writePending = false
    val session = System.currentTimeMillis().toString(36)
    fun newId(prefix: String): String = "$prefix-$session-${ids.incrementAndGet()}"

    @Synchronized fun initialize(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        writer = Handler(HandlerThread("P0EvidenceWriter").apply { start() }.looper)
        val prefs = app.getSharedPreferences("p0_diagnostics", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", false)
        prefs.getString("tail", "").orEmpty().lineSequence().filter { it.isNotEmpty() }
            .take(MAX_EVENTS).forEach { lines.addLast(it.take(MAX_LINE)) }
        if (enabled) event("APP", "PROCESS_START", "session=$session previousCountersNotCarried=true")
    }

    @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        // Never clear previous evidence; a restart/stop is a separate event.
        append("APP", if (value) "DIAGNOSTICS_START" else "DIAGNOSTICS_STOP", "session=$session")
    }

    @Synchronized fun event(scope: String, kind: String, detail: String = "") {
        if (enabled) append(scope, kind, detail)
    }

    @Synchronized fun gesture(scope: String, kind: String) {
        if (!enabled) return
        val key = "$scope/$kind"
        counts[key] = (counts[key] ?: 0) + 1
        append(scope, "GESTURE", "action=$kind count=${counts[key]} diagnosticOnly=true")
    }

    private fun append(scope: String, kind: String, detail: String) {
        val safe = detail.replace('\n', ' ').replace('\r', ' ').take(450)
        val line = "wallMs=${System.currentTimeMillis()} monoMs=${SystemClock.elapsedRealtime()} " +
            "uptimeMs=${SystemClock.uptimeMillis()} scope=$scope event=$kind $safe"
        lines.addLast(line.take(MAX_LINE))
        while (lines.size > MAX_EVENTS) lines.removeFirst()
        Log.i(TAG, line.take(MAX_LINE))
        if (!writePending) {
            writePending = true
            writer.postDelayed({
                val snapshot = synchronized(this) {
                    writePending = false
                    enabled to lines.joinToString("\n")
                }
                app.getSharedPreferences("p0_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean("enabled", snapshot.first).putString("tail", snapshot.second).apply()
            }, 500)
        }
    }

    fun rawTouch(scope: String, event: MotionEvent) {
        if (!enabled) return
        // Every delivered event is recorded. The bounded ring may evict earlier events.
        val pointers = (0 until event.pointerCount).joinToString(";") {
            "${event.getPointerId(it)}:${event.getX(it).toInt()},${event.getY(it).toInt()}"
        }
        this.event(scope, "TOUCH", "action=${MotionEvent.actionToString(event.action)} " +
            "eventTime=${event.eventTime} downTime=${event.downTime} pointers=$pointers history=${event.historySize}")
    }

    fun screen(context: Context, scope: String, signal: String, visible: Boolean?) {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        event(scope, "SCREEN_SAMPLE", "signal=$signal interactive=${power.isInteractive} " +
            "keyguardLocked=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked} visible=$visible")
    }

    @Synchronized fun summary(): String = buildString {
        appendLine("${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) target=36")
        appendLine("session=$session enabled=$enabled ring=${lines.size}/$MAX_EVENTS")
        appendLine("Counters are per Engine in this process; PREVIEW and FORMAL are separate.")
        counts.forEach { (key, value) -> appendLine("$key = $value") }
    }

    @Synchronized fun export(): String = summary() + "\n" + lines.joinToString("\n")
    @Synchronized fun tail(): String = lines.toList().takeLast(18).joinToString("\n")
}

/** In-process control only. No exported broadcasts or image URI extras. Main thread only. */
object P0Controls {
    data class EngineControl(val scope: String, val rebuild: (Boolean) -> Unit, val reload: () -> Unit)
    private val engines = linkedMapOf<String, EngineControl>()
    fun add(control: EngineControl) { engines[control.scope] = control }
    fun remove(scope: String) { engines.remove(scope) }
    fun snapshot(): List<EngineControl> = engines.values.toList()
}
