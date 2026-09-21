package dev.abdus.apps.shimmer.p0

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity

/** Arrival probe, intentionally no P3 edit/transform behaviour. Never exported. */
class P0EditActivity : ComponentActivity() {
    companion object {
        fun request(context: Context, source: String, testImage: String) {
            val request = P0Diagnostics.newId("EDIT")
            val start = SystemClock.elapsedRealtime()
            P0Diagnostics.event(source, "EDIT_REQUEST", "request=$request testImage=$testImage")
            try {
                context.startActivity(Intent(context, P0EditActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("request", request); putExtra("source", source)
                    putExtra("testImage", testImage); putExtra("start", start)
                })
                P0Diagnostics.event(source, "EDIT_REQUEST_RETURNED", "request=$request arrivalUnconfirmed=true")
            } catch (failed: Exception) {
                P0Diagnostics.event(source, "EDIT_START_EXCEPTION", "request=$request type=${failed.javaClass.simpleName}")
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderArrival("ON_CREATE")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); renderArrival("ON_NEW_INTENT")
    }
    private fun renderArrival(callback: String) {
        logArrival(callback)
        p0Column().apply {
            label("P0 编辑入口已到达", 24f)
            label("request=${intent.getStringExtra("request")}\nsource=${intent.getStringExtra("source")}\n" +
                "testImage=${intent.getStringExtra("testImage")}\n回调=$callback\n" +
                "arrivalUptimeMs=${android.os.SystemClock.uptimeMillis()}\n" +
                "此页只验证两指双击能否打开 Activity，不提供位置调整。")
            button("返回") { finish() }
        }
    }
    private fun logArrival(callback: String) {
        val elapsed = SystemClock.elapsedRealtime() - intent.getLongExtra("start", SystemClock.elapsedRealtime())
        P0Diagnostics.event(intent.getStringExtra("source") ?: "UNKNOWN", "EDIT_$callback",
            "request=${intent.getStringExtra("request")} requestToCallbackMs=$elapsed")
    }
    override fun onResume() { super.onResume(); logArrival("ON_RESUME") }
    override fun onDestroy() { logArrival("ON_DESTROY"); super.onDestroy() }
}
