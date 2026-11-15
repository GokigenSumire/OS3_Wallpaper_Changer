package dev.abdus.apps.shimmer.ui.onboarding

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Include the schema even at its default value; ordinal steps cannot be restored across schemas. */
object GuideProgressCodec {
    private val json = Json { encodeDefaults = true }
    fun encode(state: GuideProgress): String = json.encodeToString(state)
    fun restore(raw: Any, initialActive: Boolean): GuideProgress {
        val reset = GuideProgress(active = initialActive)
        if (raw !is String) return reset
        return try {
            if (json.parseToJsonElement(raw).jsonObject["flowVersion"]?.jsonPrimitive?.intOrNull != 4) return reset
            json.decodeFromString<GuideProgress>(raw).let {
                if (it.step in GuideStep.entries.indices) it else reset
            }
        } catch (_: Exception) { reset }
    }
}
