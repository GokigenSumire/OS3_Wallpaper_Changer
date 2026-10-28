package dev.abdus.apps.shimmer.p4

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.ZoneId
import java.util.TimeZone
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/** A formal wallpaper Engine that can execute scheduled switches. */
interface ScheduleTarget {
    /** Execute [dispatch] and report back exactly once through the coordinator. */
    fun onScheduledSwitch(dispatch: ScheduleDispatch)

    /** The matching rules changed: re-read the effective catalog. */
    fun onScheduleCatalogChanged()
}

/** Clocks and device state; replaced in tests. */
interface ScheduleEnvironment {
    fun now(): ScheduleNow
}

internal class AndroidScheduleEnvironment(context: Context) : ScheduleEnvironment {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    /** Fixed for the life of the process, which never outlives a boot. */
    private val bootCount: Int by lazy {
        runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
    }

    override fun now(): ScheduleNow = ScheduleNow(
        elapsed = SystemClock.elapsedRealtime(),
        wall = System.currentTimeMillis(),
        zone = ZoneId.systemDefault(),
        bootCount = bootCount,
        interactive = power?.isInteractive ?: true,
        locked = keyguard?.isKeyguardLocked ?: false,
    )
}

/**
 * Process-wide scheduler shared by the formal Engines. Owns the [ScheduleMachine], the main
 * thread timer (only while a wallpaper is visible), the dynamic screen/clock receivers (only while
 * an Engine is registered) and the diagnostics log. Main thread only.
 *
 * Dispatches are posted, never delivered inside a machine call, so an Engine reporting an outcome
 * synchronously cannot re-enter the machine.
 */
class ScheduleCoordinator internal constructor(context: Context, private val env: ScheduleEnvironment,
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME) {
    private val app = context.applicationContext
    private val store = ScheduleStore(app, wallpaperTarget)
    private val machine = ScheduleMachine(store.loadSettings(), store.loadState())
    private val handler = Handler(Looper.getMainLooper())
    private val targets = LinkedHashMap<String, Entry>()
    private var visibleOrder = 0L
    private var receiversRegistered = false
    private var persisted: ScheduleState = machine.state

    private class Entry(val target: ScheduleTarget, var visible: Boolean = false, var order: Long = 0L)

    private val tick = Runnable { drive { machine.evaluate(it) } }

    /** Only the settings key: our own state/log writes must not loop back. */
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ScheduleStore.KEY_SETTINGS) drive { machine.updateSettings(store.loadSettings(), it) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> drive { machine.onScreenOff(it) }
                Intent.ACTION_SCREEN_ON -> drive { machine.onScreenOn(it) }
                Intent.ACTION_USER_PRESENT -> drive { machine.onUserPresent(it) }
                Intent.ACTION_TIME_CHANGED -> drive { machine.onClockChanged(it, "系统时间已修改") }
                Intent.ACTION_TIMEZONE_CHANGED -> {
                    // Drop the cached default so ZoneId.systemDefault() reads the new zone now.
                    TimeZone.setDefault(null)
                    drive { machine.onClockChanged(it, "时区已修改：" + ZoneId.systemDefault().id) }
                }
            }
        }
    }

    init {
        store.registerListener(settingsListener)
    }

    // ---- Engines -----------------------------------------------------------------------------

    /** Formal Engines only; previews never register and never execute scheduled switches. */
    fun register(id: String, target: ScheduleTarget) {
        targets[id] = Entry(target)
        if (!receiversRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            // Protected system broadcasts only, registered like the P0-verified USER_PRESENT
            // receiver. RECEIVER_NOT_EXPORTED would drop USER_PRESENT, which SystemUI sends.
            app.registerReceiver(receiver, filter)
            receiversRegistered = true
        }
        drive { machine.evaluate(it) }
    }

    fun unregister(id: String) {
        if (targets.remove(id) == null) return
        if (targets.isEmpty() && receiversRegistered) {
            runCatching { app.unregisterReceiver(receiver) }
            receiversRegistered = false
        }
        drive { machine.onVisibility(anyVisible(), it) }
    }

    fun onVisibility(id: String, visible: Boolean) {
        val entry = targets[id] ?: return
        entry.visible = visible
        if (visible) entry.order = ++visibleOrder
        drive { machine.onVisibility(anyVisible(), it) }
    }

    fun onCommitted(id: String, seq: Long, catalogSize: Int) {
        if (id in targets) drive { machine.onCommitted(seq, catalogSize, it) }
    }

    fun onOutcome(id: String, seq: Long, outcome: SwitchOutcome, detail: String = "") {
        if (id in targets) drive { machine.onOutcome(seq, outcome, it, detail) }
    }

    fun onManualCommit(id: String, detail: String) {
        if (id in targets) drive { machine.onManualCommit(it, detail) }
    }

    fun onEditEnded(id: String) {
        if (id in targets) drive { machine.onEditEnded(it) }
    }

    private fun anyVisible(): Boolean = targets.values.any { it.visible }

    /** The most recently shown visible Engine executes; the others follow through the snapshot. */
    private fun executor(): ScheduleTarget? = targets.values.filter { it.visible }.maxByOrNull { it.order }?.target

    // ---- Machine plumbing --------------------------------------------------------------------

    private fun drive(call: (ScheduleNow) -> ScheduleEffects) {
        val effects = runCatching { call(env.now()) }
            .onFailure { Log.e(TAG, "schedule step failed", it) }
            .getOrDefault(ScheduleEffects.NONE)
        persist()
        if (effects.refreshCatalog) {
            targets.values.map { it.target }.forEach { target ->
                handler.post { if (targets.values.any { it.target === target }) target.onScheduleCatalogChanged() }
            }
        }
        effects.dispatch?.let { dispatch -> handler.post { deliver(dispatch) } }
        reschedule()
    }

    private fun deliver(dispatch: ScheduleDispatch) {
        val executor = executor()
        if (executor == null) {
            drive { machine.onOutcome(dispatch.seq, SwitchOutcome.DROPPED, it, "没有可见的正式 Engine") }
        } else {
            executor.onScheduledSwitch(dispatch)
        }
    }

    private fun persist() {
        val events = machine.drainEvents()
        val state = machine.state
        if (events.isEmpty() && state == persisted) return
        runCatching { store.commitRun(state, events, env.now().wall) }
            .onFailure { Log.e(TAG, "schedule state not saved", it) }
        persisted = state
        events.forEach { Log.d(TAG, it.line(ZoneId.systemDefault())) }
    }

    private fun reschedule() {
        handler.removeCallbacks(tick)
        if (targets.isEmpty()) return
        machine.nextCheckDelay(env.now())?.let { handler.postDelayed(tick, it) }
    }

    // ---- P4 page -----------------------------------------------------------------------------

    fun statusLines(): List<String> {
        val now = env.now()
        val lines = machine.statusLines(now)
        persist()
        val next = if (targets.isEmpty()) null else machine.nextCheckDelay(now)
        return lines + listOf(
            "已注册正式 Engine：${targets.size}（可见 ${targets.values.count { it.visible }}）",
            "下次检查：" + (next?.let { "约 ${(it + 999L) / 1_000L} 秒后" } ?: "无（壁纸不可见时不计时）"),
        )
    }

    /** Status, threshold report and recent events, as copied from the P4 page. */
    fun diagnosticsText(): String {
        val zone = ZoneId.systemDefault()
        return (statusLines() + listOf("", "【统计】") + store.loadStats().report(zone) +
            listOf("", "【最近事件】") + store.loadLog().map { it.line(zone) }).joinToString("\n")
    }

    fun clearDiagnostics() {
        store.clearDiagnostics(env.now().wall)
    }

    companion object {
        private const val TAG = "P4Schedule"

        @Volatile private var instance: ScheduleCoordinator? = null
        @Volatile private var lockInstance: ScheduleCoordinator? = null

        fun get(context: Context, target: WallpaperTarget = WallpaperTarget.HOME): ScheduleCoordinator = synchronized(this) {
            when (target) {
                WallpaperTarget.HOME -> instance ?: ScheduleCoordinator(context, AndroidScheduleEnvironment(context), target).also { instance = it }
                WallpaperTarget.LOCK -> lockInstance ?: ScheduleCoordinator(context, AndroidScheduleEnvironment(context), target).also { lockInstance = it }
            }
        }
    }
}
