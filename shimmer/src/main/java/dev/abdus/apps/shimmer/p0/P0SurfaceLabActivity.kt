package dev.abdus.apps.shimmer.p0

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.SurfaceHolder
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** Controlled *Android Surface* experiment. Logs explicitly identify LAB, never a wallpaper Engine. */
class P0SurfaceLabActivity : ComponentActivity() {
    private lateinit var host: FrameLayout
    private var surface: ProbeView? = null
    private var generation = 0
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p0Column().apply {
            label("P0 Surface 实验室", 24f)
            label("此页是真实 SurfaceView/context 的受控对照实验，日志标注 LAB。它不是 Wallpaper Engine，" +
                "不能代替预览/正式壁纸的生命周期结果。系统预览往返仍需独立记录 ANDROID_SURFACE 事件。")
            button("销毁并重新建立实验 Surface") { rebuild() }
            button("返回") { finish() }
            host = FrameLayout(this@P0SurfaceLabActivity)
            addView(host, android.widget.LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
        }
        rebuild()
    }

    private fun rebuild() {
        surface?.onPause()
        host.removeAllViews() // Detaches actual SurfaceView; not a simulated callback.
        generation++
        surface = ProbeView(this, "LAB/${P0Diagnostics.newId("SURFACE")}/$generation").also {
            host.addView(it, FrameLayout.LayoutParams(-1, -1))
            if (resumed) it.onResume()
        }
    }
    override fun onResume() { super.onResume(); resumed = true; surface?.onResume() }
    override fun onPause() { resumed = false; surface?.onPause(); super.onPause() }

    private class ProbeView(context: Context, private val scope: String) : GLSurfaceView(context) {
        init {
            setEGLContextClientVersion(3)
            setEGLContextFactory(object : EGLContextFactory {
                override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
                    val created = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, intArrayOf(0x3098, 3, EGL10.EGL_NONE))
                    P0Diagnostics.event(scope, "LAB_CONTEXT_CREATE", "valid=${created != EGL10.EGL_NO_CONTEXT} thread=${Thread.currentThread().id}")
                    return created
                }
                override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
                    val success = egl.eglDestroyContext(display, context)
                    P0Diagnostics.event(scope, "LAB_CONTEXT_DESTROY", "success=$success thread=${Thread.currentThread().id} programs=0 textures=0")
                }
            })
            setRenderer(object : Renderer {
                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    GLES30.glClearColor(0.05f, 0.3f, 0.4f, 1f)
                    P0Diagnostics.event(scope, "LAB_RENDERER_CREATE", "thread=${Thread.currentThread().id}")
                }
                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    GLES30.glViewport(0, 0, width, height)
                    P0Diagnostics.event(scope, "LAB_RESIZE", "width=$width height=$height")
                }
                override fun onDrawFrame(gl: GL10?) {
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    P0Diagnostics.event(scope, "LAB_DRAW", "glError=${GLES30.glGetError()}")
                }
            })
            renderMode = RENDERMODE_WHEN_DIRTY
        }
        override fun surfaceCreated(holder: SurfaceHolder) {
            P0Diagnostics.event(scope, "LAB_ANDROID_SURFACE_CREATE", "valid=${holder.surface.isValid}")
            super.surfaceCreated(holder)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            P0Diagnostics.event(scope, "LAB_ANDROID_SURFACE_DESTROY")
            super.surfaceDestroyed(holder)
        }
    }
}
