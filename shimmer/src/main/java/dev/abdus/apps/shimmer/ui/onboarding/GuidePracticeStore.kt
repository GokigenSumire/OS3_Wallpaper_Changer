package dev.abdus.apps.shimmer.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class GuidePracticeRequest(val id: String, val target: String, val step: GuideStep, val created: Long,
    val completed: Boolean = false, val imageId: String? = null)
data class GuidePracticeTicket(val request: GuidePracticeRequest, val beforeImage: String)

/** Passive acknowledgements only: this store never changes wallpaper, history or editor authorization. */
class GuidePracticeStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.applicationContext.getSharedPreferences("guide_actual_practice", Context.MODE_PRIVATE)
    fun current(): GuidePracticeRequest? = try {
        prefs.getString("request", null)?.let { Json.decodeFromString<GuidePracticeRequest>(it) }
            ?.takeIf { now() >= it.created && now() - it.created <= 600_000 }
    } catch (_: Exception) { null }
    fun arm(target: WallpaperTarget, step: GuideStep): GuidePracticeRequest {
        require(GuideFlow.expectedGesture(step) != null)
        return GuidePracticeRequest(UUID.randomUUID().toString(), target.name, step, now()).also(::save)
    }
    fun capture(target: WallpaperTarget, action: String, beforeImage: String?): GuidePracticeTicket? {
        val request = current() ?: return null
        if (request.completed || request.target != target.name || GuideFlow.expectedGesture(request.step) != action || beforeImage.isNullOrBlank()) return null
        return GuidePracticeTicket(request, beforeImage)
    }
    fun presented(ticket: GuidePracticeTicket, imageId: String): Boolean {
        if (ticket.request.step !in setOf(GuideStep.GESTURE_NEXT, GuideStep.GESTURE_PREVIOUS) || imageId == ticket.beforeImage || imageId.isBlank()) return false
        return complete(ticket.request.id, ticket.request.target, ticket.request.step, imageId)
    }
    fun editorOpened(sessionId: String?, target: WallpaperTarget, imageId: String): Boolean {
        if (sessionId == null || imageId.isBlank()) return false
        return complete(sessionId, target.name, GuideStep.GESTURE_EDIT, imageId)
    }
    private fun complete(id: String, target: String, step: GuideStep, imageId: String): Boolean {
        val request = current() ?: return false
        if (request.id != id || request.target != target || request.step != step || request.completed) return false
        save(request.copy(completed = true, imageId = imageId)); return true
    }
    fun cancel(sessionId: String?) { if (sessionId != null && current()?.id == sessionId) prefs.edit().remove("request").apply() }
    fun observe(changed: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private fun save(request: GuidePracticeRequest) { prefs.edit().putString("request", Json.encodeToString(request)).apply() }
    companion object { const val EXTRA_SESSION = "personal.wallpaper.os3.guide.PRACTICE_SESSION" }
}
