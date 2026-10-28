package dev.abdus.apps.shimmer.p10.capability

import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import dev.abdus.apps.shimmer.p0.P0GestureDetector
import dev.abdus.apps.shimmer.p0.P0GestureMachine
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import java.util.concurrent.atomic.AtomicLong

class HomeCapabilityWallpaperService : CapabilityWallpaperService(WallpaperTarget.HOME)
class LockCapabilityWallpaperService : CapabilityWallpaperService(WallpaperTarget.LOCK)

/**
 * Deliberately not a second player: two explicit components draw different Canvas markers.
 * No production stores, GL, editor, schedules or Root control are accessed by this service.
 */
abstract class CapabilityWallpaperService(private val marker: WallpaperTarget) : WallpaperService() {
    companion object { private val engineIds = AtomicLong() }

    override fun onCreateEngine(): Engine = CapabilityEngine()

    inner class CapabilityEngine : Engine() {
        private val evidenceId = "${marker.name}-${engineIds.incrementAndGet()}"
        private var created = false
        private var visible = false
        private var surfaceReady = false
        private var detector: P0GestureDetector? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // The platform attaches its wrapper before onCreate, NOT before the constructor.
            val flags = attachedFlags()
            CapabilityObservations.record { now -> created(evidenceId, marker, flags, isPreview, now) }
            created = true
            detector = P0GestureDetector(this@CapabilityWallpaperService) { action ->
                val kind = when (action) {
                    P0GestureMachine.DOUBLE_NEXT -> CapabilityEventKind.DOUBLE_TAP
                    P0GestureMachine.TRIPLE_PREV -> CapabilityEventKind.TRIPLE_TAP
                    P0GestureMachine.TWO_FINGER_EDIT -> CapabilityEventKind.TWO_FINGER_DOUBLE_TAP
                    else -> null
                }
                if (kind != null && created && visible) {
                    CapabilityObservations.record { now -> gesture(evidenceId, kind, now) }
                    drawMarker()
                }
            }
            val touchRequested = try { setTouchEventsEnabled(true); true }
                catch (_: RuntimeException) {
                    CapabilityObservations.record { now -> failure(evidenceId, now) }
                    false
                }
            CapabilityObservations.record { now -> touchRequested(evidenceId, touchRequested, now) }
            try { setOffsetNotificationsEnabled(false) }
            catch (_: RuntimeException) { CapabilityObservations.record { now -> failure(evidenceId, now) } }
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            if (Build.VERSION.SDK_INT >= 34) super.onWallpaperFlagsChanged(which)
            if (!created) return
            val actual = attachedFlags()
            // Keep callback and sampled values distinct: queued callbacks may lag the getter.
            CapabilityObservations.record { now -> flagsChanged(evidenceId, actual, which, now) }
            detector?.reset()
            drawMarker()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            this.visible = visible
            if (!created) return
            CapabilityObservations.record { now -> visibility(evidenceId, visible, now) }
            if (!visible) detector?.reset() else drawMarker()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            if (created) CapabilityObservations.record { now -> surfaceCreated(evidenceId, now) }
            drawMarker()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (created) CapabilityObservations.record { now -> surfaceChanged(evidenceId, width, height, now) }
            drawMarker()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawMarker()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            detector?.reset()
            if (created) CapabilityObservations.record { now -> surfaceDestroyed(evidenceId, now) }
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (!created) return
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> CapabilityTouchAction.DOWN
                MotionEvent.ACTION_UP -> CapabilityTouchAction.UP
                MotionEvent.ACTION_POINTER_DOWN -> CapabilityTouchAction.POINTER_DOWN
                MotionEvent.ACTION_POINTER_UP -> CapabilityTouchAction.POINTER_UP
                MotionEvent.ACTION_MOVE -> CapabilityTouchAction.MOVE
                MotionEvent.ACTION_CANCEL -> CapabilityTouchAction.CANCEL
                else -> CapabilityTouchAction.OTHER
            }
            CapabilityObservations.recordTouch { now -> touch(evidenceId, action, event.pointerCount, now) }
            if (visible) detector?.onTouchEvent(event)
        }

        override fun onDestroy() {
            detector?.close()
            detector = null
            if (created) CapabilityObservations.record { now -> destroyed(evidenceId, now) }
            created = false
            surfaceReady = false
            super.onDestroy()
        }

        private fun attachedFlags(): Int? = if (Build.VERSION.SDK_INT >= 34) {
            try { wallpaperFlags } catch (_: RuntimeException) { null }
        } else null

        private fun drawMarker() {
            if (!created || !visible || !surfaceReady) return
            val row = CapabilityObservations.snapshot().engines.firstOrNull { it.id == evidenceId } ?: return
            var canvas: android.graphics.Canvas? = null
            var posted = false
            try {
                if (!surfaceHolder.surface.isValid) return
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(if (marker == WallpaperTarget.HOME) Color.rgb(24, 65, 106) else Color.rgb(94, 40, 92))
                    val density = resources.displayMetrics.density
                    val left = 24f * density
                    var top = canvas.height * .36f
                    paint.textSize = 24f * density
                    paint.isFakeBoldText = true
                    canvas.drawText("${marker.name} 能力标记", left, top, paint)
                    paint.textSize = 15f * density
                    paint.isFakeBoldText = false
                    val lines = listOf(
                        if (row.route.isPreview) "系统预览（不代表正式目标）" else "正式实例（仅观测，不播放图库）",
                        "实际 flags=${row.route.observedFlags ?: "UNKNOWN"} / ${row.route.exposure}",
                        "原始触摸 ${row.rawTouchEvents} / 最多 ${row.maxPointerCount} 指",
                        "双击 ${row.doubleTaps} / 三击 ${row.tripleTaps} / 双指双击 ${row.twoFingerDoubleTaps}",
                        "组件标记不代表系统实际分配目标",
                    )
                    lines.forEach { line -> top += 30f * density; canvas.drawText(line, left, top, paint) }
                }
            } catch (_: RuntimeException) {
                CapabilityObservations.record { now -> failure(evidenceId, now) }
            } finally {
                if (canvas != null) {
                    try { surfaceHolder.unlockCanvasAndPost(canvas); posted = true }
                    catch (_: RuntimeException) { CapabilityObservations.record { now -> failure(evidenceId, now) } }
                }
            }
            if (posted) CapabilityObservations.record { now -> drawn(evidenceId, now) }
        }
    }
}
