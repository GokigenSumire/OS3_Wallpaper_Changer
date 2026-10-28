package dev.abdus.apps.shimmer.p10.input

import android.app.PendingIntent

/** 只含有限状态和不透明票据；原图URI、路径、坐标不跨进程。 */
data class LockHostState(val ready: Boolean, val ticket: String, val reason: String)
data class LockHostResult(val status: String, val editLaunch: PendingIntent? = null)

/** snapshot须返回线程安全的不可变快照；其余方法由宿主保证在Main调用。 */
interface LockHostEndpoint {
    fun snapshot(): LockHostState
    fun dispatch(action: Int, ticket: String, requestId: String, complete: (LockHostResult) -> Unit)
    /** 只撤对应请求；旧连接迟到不能取消新请求或前台手动编辑。 */
    fun revoke(requestId: String)
}
