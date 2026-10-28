package dev.abdus.apps.shimmer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.abdus.apps.shimmer.p3.FitMode
import dev.abdus.apps.shimmer.p3.P3Defaults
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import java.util.IdentityHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

object DuotoneBlendModeSerializer : KSerializer<DuotoneBlendMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DuotoneBlendMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DuotoneBlendMode) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): DuotoneBlendMode {
        val string = decoder.decodeString()
        return try {
            DuotoneBlendMode.valueOf(string)
        } catch (e: IllegalArgumentException) {
            DuotoneBlendMode.NORMAL
        }
    }
}

@Serializable
data class DuotoneSettings(
    val enabled: Boolean,
    val alwaysOn: Boolean,
    val lightColor: Int,
    val darkColor: Int,
    val presetIndex: Int,
    @Serializable(with = DuotoneBlendModeSerializer::class)
    val blendMode: DuotoneBlendMode = DuotoneBlendMode.NORMAL,
)

@Serializable
data class ChromaticAberrationSettings(
    val enabled: Boolean,
    val intensity: Float,
    val fadeDurationMillis: Long,
)

@Serializable
data class ImageFolder(
    val uri: String,
    val thumbnailUri: String? = null,
    val imageCount: Int? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ImageCycleSettings(
    val enabled: Boolean,
    val intervalMillis: Long,
    val cycleOnUnlock: Boolean,
)

class WallpaperPreferences(
    private val prefs: SharedPreferences,
    private val domainPrefs: SharedPreferences = prefs,
    val target: WallpaperTarget = WallpaperTarget.HOME,
) {
    private data class ListenerRegistration(
        val global: SharedPreferences.OnSharedPreferenceChangeListener,
        val domain: SharedPreferences.OnSharedPreferenceChangeListener,
    )
    private val listenerRegistrations = IdentityHashMap<SharedPreferences.OnSharedPreferenceChangeListener, ListenerRegistration>()

    companion object {
        private const val PREFS_NAME = "shimmer_wallpaper_prefs"
        const val LOCK_DOMAIN_NAME = "p10_lock_wallpaper_prefs"
        /** 所有界面／引擎实例共享；图片停用集合的读改写与清空不可互相覆盖。 */
        private val disabledImagesLock = Any()

        const val KEY_BLUR_AMOUNT = "wallpaper_blur_amount"
        const val KEY_DIM_AMOUNT = "wallpaper_dim_amount"
        const val KEY_DUOTONE_SETTINGS = "wallpaper_duotone_settings"
        const val KEY_IMAGE_CYCLE_SETTINGS = "wallpaper_image_cycle_settings"
        const val KEY_EFFECT_TRANSITION_DURATION = "wallpaper_effect_transition_duration"
        const val KEY_BLUR_ON_SCREEN_LOCK = "wallpaper_blur_on_screen_lock"
        const val KEY_BLUR_TIMEOUT_ENABLED = "wallpaper_blur_timeout_enabled"
        const val KEY_BLUR_TIMEOUT_MILLIS = "wallpaper_blur_timeout_millis"
        const val KEY_UNBLUR_ON_UNLOCK = "wallpaper_unblur_on_unlock"
        const val KEY_LAST_SELECTED_TAB = "settings_last_selected_tab"
        const val KEY_GRAIN_SETTINGS = "wallpaper_grain_settings"
        const val KEY_CHROMATIC_ABERRATION_SETTINGS = "wallpaper_chromatic_aberration_settings"
        const val KEY_FAVORITES_FOLDER_URI = "favorites_folder_uri"
        const val KEY_SHARED_FOLDER_URI = "shared_folder_uri"
        const val KEY_GESTURE_SETTINGS = "wallpaper_gesture_settings"
        const val KEY_P2_STATIC_TEST_MODE = "p2_static_test_mode"
        const val KEY_SHIMMER_EFFECTS_RETIRED = "p9_shimmer_effects_retired"
        const val KEY_P3_DEFAULT_FIT_MODE = "p3_default_fit_mode"
        const val KEY_P3_DEFAULT_BLUR_FILL = "p3_default_blur_fill"
        const val KEY_P3_INHERIT_PREVIOUS = "p3_inherit_previous"
        const val KEY_DISABLED_IMAGES = "disabled_images"

        /** Keys whose change can alter the placement of an image without its own saved config. */
        val P3_DEFAULT_KEYS = setOf(KEY_P3_DEFAULT_FIT_MODE, KEY_P3_DEFAULT_BLUR_FILL, KEY_P3_INHERIT_PREVIOUS)
        private val DOMAIN_KEYS = P3_DEFAULT_KEYS + KEY_DISABLED_IMAGES

        const val DEFAULT_BLUR_AMOUNT = 0.5f
        const val DEFAULT_DIM_AMOUNT = 0.1f
        const val DEFAULT_DUOTONE_ENABLED = false
        const val DEFAULT_DUOTONE_LIGHT = 0xFFFFD000.toInt()
        const val DEFAULT_DUOTONE_DARK = 0xFF696969.toInt()
        const val DEFAULT_DUOTONE_ALWAYS_ON = false
        const val DEFAULT_IMAGE_CYCLE_ENABLED = true
        const val DEFAULT_TRANSITION_INTERVAL_MILLIS = 30_000L
        const val DEFAULT_CYCLE_IMAGE_ON_UNLOCK = false
        const val DEFAULT_EFFECT_TRANSITION_DURATION_MILLIS = 1500L
        const val DEFAULT_BLUR_TIMEOUT_MILLIS = 30_000L
        const val MIN_BLUR_TIMEOUT_MILLIS = 5_000L
        const val MAX_BLUR_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_GRAIN_ENABLED = false
        const val DEFAULT_GRAIN_AMOUNT = 0.18f
        const val DEFAULT_GRAIN_SCALE = 0.5f
        const val DEFAULT_CHROMATIC_ABERRATION_ENABLED = true
        const val DEFAULT_CHROMATIC_ABERRATION_INTENSITY = 0.5f
        const val DEFAULT_CHROMATIC_ABERRATION_FADE_DURATION = 500L

        fun create(context: Context, target: WallpaperTarget = WallpaperTarget.HOME): WallpaperPreferences {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val domainPrefs = if (target == WallpaperTarget.HOME) prefs else
                context.getSharedPreferences(LOCK_DOMAIN_NAME, Context.MODE_PRIVATE)
            return WallpaperPreferences(prefs, domainPrefs, target)
        }
    }

    fun getFavoritesFolderUri(): Uri? {
        val value = prefs.getString(KEY_FAVORITES_FOLDER_URI, null) ?: return null
        return value.toUri()
    }

    fun setFavoritesFolderUri(uri: Uri?) {
        prefs.edit {
            if (uri == null) {
                remove(KEY_FAVORITES_FOLDER_URI)
            } else {
                putString(KEY_FAVORITES_FOLDER_URI, uri.toString())
            }
        }
    }

    fun getSharedFolderUri(): Uri? {
        val value = prefs.getString(KEY_SHARED_FOLDER_URI, null) ?: return null
        return value.toUri()
    }

    fun setSharedFolderUri(uri: Uri?) {
        prefs.edit {
            if (uri == null) {
                remove(KEY_SHARED_FOLDER_URI)
            } else {
                putString(KEY_SHARED_FOLDER_URI, uri.toString())
            }
        }
    }

    fun favoritesFolderUriFlow(): Flow<Uri?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_FAVORITES_FOLDER_URI) trySend(getFavoritesFolderUri())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getFavoritesFolderUri())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun sharedFolderUriFlow(): Flow<Uri?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SHARED_FOLDER_URI) trySend(getSharedFolderUri())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getSharedFolderUri())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getBlurAmount(): Float =
        prefs.getFloat(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT)

    fun getDimAmount(): Float =
        prefs.getFloat(KEY_DIM_AMOUNT, DEFAULT_DIM_AMOUNT)

    /**
     * Get grain settings as a single object.
     */
    fun getGrainSettings(): GrainSettings {
        val json = prefs.getString(KEY_GRAIN_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<GrainSettings>(json)
            } catch (e: Exception) {
                getDefaultGrainSettings()
            }
        } else {
            getDefaultGrainSettings()
        }
    }

    /**
     * Set grain settings as a single atomic operation.
     * This triggers only ONE SharedPreferences change notification.
     */
    fun setGrainSettings(settings: GrainSettings) {
        val json = Json.encodeToString(settings)
        prefs.edit {
            putString(KEY_GRAIN_SETTINGS, json)
        }
    }

    private fun getDefaultGrainSettings() = GrainSettings(
        enabled = DEFAULT_GRAIN_ENABLED,
        amount = DEFAULT_GRAIN_AMOUNT,
        scale = DEFAULT_GRAIN_SCALE,
    )

    fun setBlurAmount(amount: Float) {
        prefs.edit {
            putFloat(KEY_BLUR_AMOUNT, amount.coerceIn(0f, 1f))
        }
    }

    fun setDimAmount(amount: Float) {
        prefs.edit {
            putFloat(KEY_DIM_AMOUNT, amount.coerceIn(0f, 1f))
        }
    }

    /**
     * Get chromatic aberration settings as a single object.
     */
    fun getChromaticAberrationSettings(): ChromaticAberrationSettings {
        val json = prefs.getString(KEY_CHROMATIC_ABERRATION_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<ChromaticAberrationSettings>(json)
            } catch (e: Exception) {
                getDefaultChromaticAberrationSettings()
            }
        } else {
            getDefaultChromaticAberrationSettings()
        }
    }

    /**
     * Set chromatic aberration settings as a single atomic operation.
     * This triggers only ONE SharedPreferences change notification.
     */
    fun setChromaticAberrationSettings(settings: ChromaticAberrationSettings) {
        val json = Json.encodeToString(settings)
        prefs.edit {
            putString(KEY_CHROMATIC_ABERRATION_SETTINGS, json)
        }
    }

    private fun getDefaultChromaticAberrationSettings() = ChromaticAberrationSettings(
        enabled = DEFAULT_CHROMATIC_ABERRATION_ENABLED,
        intensity = DEFAULT_CHROMATIC_ABERRATION_INTENSITY,
        fadeDurationMillis = DEFAULT_CHROMATIC_ABERRATION_FADE_DURATION,
    )

    /**
     * Get duotone settings as a single object.
     */
    fun getDuotoneSettings(): DuotoneSettings {
        val json = prefs.getString(KEY_DUOTONE_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<DuotoneSettings>(json)
            } catch (e: Exception) {
                getDefaultDuotoneSettings()
            }
        } else {
            getDefaultDuotoneSettings()
        }
    }

    /**
     * Set duotone settings as a single atomic operation.
     * This triggers only ONE SharedPreferences change notification.
     */
    fun setDuotoneSettings(settings: DuotoneSettings) {
        val json = Json.encodeToString(settings)
        prefs.edit {
            putString(KEY_DUOTONE_SETTINGS, json)
        }
    }

    private fun getDefaultDuotoneSettings() = DuotoneSettings(
        enabled = DEFAULT_DUOTONE_ENABLED,
        alwaysOn = DEFAULT_DUOTONE_ALWAYS_ON,
        lightColor = DEFAULT_DUOTONE_LIGHT,
        darkColor = DEFAULT_DUOTONE_DARK,
        presetIndex = -1,
        blendMode = DuotoneBlendMode.NORMAL,
    )

    fun getDuotonePresetIndex(): Int =
        getDuotoneSettings().presetIndex

    fun applyDuotonePreset(
        lightColor: Int,
        darkColor: Int,
        enabled: Boolean = true,
        presetIndex: Int = -1,
    ) {
        val current = getDuotoneSettings()
        setDuotoneSettings(
            current.copy(
                enabled = enabled,
                lightColor = lightColor,
                darkColor = darkColor,
                presetIndex = presetIndex,
            ),
        )
    }

    /**
     * Get image cycle settings as a single object.
     */
    fun getImageCycleSettings(): ImageCycleSettings {
        val json = prefs.getString(KEY_IMAGE_CYCLE_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<ImageCycleSettings>(json)
            } catch (e: Exception) {
                getDefaultImageCycleSettings()
            }
        } else {
            getDefaultImageCycleSettings()
        }
    }

    /**
     * Set image cycle settings as a single atomic operation.
     */
    fun setImageCycleSettings(settings: ImageCycleSettings) {
        val json = Json.encodeToString(settings)
        prefs.edit {
            putString(KEY_IMAGE_CYCLE_SETTINGS, json)
        }
    }

    /** P2 acceptance mode keeps the selected image still and removes visual preset noise. */
    fun isP2StaticTestMode(): Boolean =
        prefs.getBoolean(KEY_P2_STATIC_TEST_MODE, false)

    fun setP2StaticTestMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_P2_STATIC_TEST_MODE, enabled) }
    }

    /**
     * P9-STATIC-01：该开关是否已被任一界面写过。
     *
     * P2 页需要"首次进入时为确定性画面"，但不能每次 onCreate 都覆盖用户选择。用这个判断把
     * "落默认值"和"用户已决定"区分开：返回 false 只在偏好文件里从未出现过这个键时成立。
     */
    fun hasP2StaticTestModeChoice(): Boolean =
        prefs.contains(KEY_P2_STATIC_TEST_MODE)

    /**
     * P9-STATIC-SHIMMER-01：关掉上游 Shimmer 的全部视觉特效（模糊、暗化、双色、颗粒、色差涟漪、
     * 特效过渡、锁屏/超时模糊、解锁去模糊）。P5 图片过渡不在这里，不受影响。
     *
     * 关闭静态验收模式时新旧两个开关都调用这里。版本 39 之前只有旧 P2 页会清零，新 UI 的开关只翻转
     * 标志，首次安装设备上这些特效仍是上游默认值（模糊 0.5、暗化 0.1、色差开启），关闭静态模式后
     * 全部显露。一次 edit 提交，壁纸只收到一批变化。
     */
    fun disableShimmerEffects() {
        val duotone = Json.encodeToString(getDuotoneSettings().copy(enabled = false, alwaysOn = false))
        val grain = Json.encodeToString(getGrainSettings().copy(enabled = false))
        val chromatic = Json.encodeToString(getChromaticAberrationSettings().copy(enabled = false))
        prefs.edit {
            putLong(KEY_EFFECT_TRANSITION_DURATION, 0L)
            putString(KEY_DUOTONE_SETTINGS, duotone)
            putString(KEY_GRAIN_SETTINGS, grain)
            putString(KEY_CHROMATIC_ABERRATION_SETTINGS, chromatic)
            putFloat(KEY_BLUR_AMOUNT, 0f)
            putFloat(KEY_DIM_AMOUNT, 0f)
            putBoolean(KEY_BLUR_ON_SCREEN_LOCK, false)
            putBoolean(KEY_BLUR_TIMEOUT_ENABLED, false)
            putBoolean(KEY_UNBLUR_ON_UNLOCK, false)
        }
    }

    /**
     * 关闭静态验收模式。顺序是先清特效再翻转标志：反过来，壁纸在两次写入之间会按上游特效画一帧。
     */
    fun exitStaticTestMode() {
        disableShimmerEffects()
        setP2StaticTestMode(false)
    }

    /**
     * P9-STATIC-SHIMMER-01：每个安装只执行一次的上游特效退役，返回本次是否执行。
     *
     * 上游设置页自 P8 起已不可达，用户无从有意开启这些特效；仍为上游默认值的设备（首次安装、
     * 从未经过旧 P2 页关闭静态模式）因此统一清零一次。标记写入后不再重复，以后若重新提供特效
     * 设置，用户的选择不会被启动覆盖。
     */
    fun retireShimmerEffectsOnce(): Boolean {
        if (prefs.getBoolean(KEY_SHIMMER_EFFECTS_RETIRED, false)) return false
        disableShimmerEffects()
        prefs.edit { putBoolean(KEY_SHIMMER_EFFECTS_RETIRED, true) }
        return true
    }

    /** 当前是否有任何上游特效仍会改变画面；只供诊断与测试使用。 */
    fun hasActiveShimmerEffects(): Boolean =
        getBlurAmount() > 0f || getDimAmount() > 0f ||
            getDuotoneSettings().enabled || getGrainSettings().enabled ||
            getChromaticAberrationSettings().enabled || getEffectTransitionDurationMillis() > 0L ||
            isBlurOnScreenLockEnabled() || isBlurTimeoutEnabled()

    /** P3 global placement defaults; an unknown stored fit mode reads as COVER with smartFit. */
    fun getP3Defaults(): P3Defaults {
        val storedFitMode = domainPrefs.getString(KEY_P3_DEFAULT_FIT_MODE, null)
        val recognizedFitMode = FitMode.entries.firstOrNull { it.name == storedFitMode }
        val isSmartOrUnknown = storedFitMode == null || storedFitMode == "SMART" || recognizedFitMode == null
        val fitMode = when {
            isSmartOrUnknown -> FitMode.COVER
            else -> recognizedFitMode
        }
        return P3Defaults(
            fitMode = fitMode,
            blurFill = domainPrefs.getBoolean(KEY_P3_DEFAULT_BLUR_FILL, P3Defaults().blurFill),
            inheritPrevious = domainPrefs.getBoolean(KEY_P3_INHERIT_PREVIOUS, P3Defaults().inheritPrevious),
            smartFit = isSmartOrUnknown,
        )
    }

    fun setP3SmartFit() {
        domainPrefs.edit { putString(KEY_P3_DEFAULT_FIT_MODE, "SMART") }
    }

    fun setP3DefaultFitMode(mode: FitMode) {
        domainPrefs.edit { putString(KEY_P3_DEFAULT_FIT_MODE, mode.name) }
    }

    fun setP3DefaultBlurFill(enabled: Boolean) {
        domainPrefs.edit { putBoolean(KEY_P3_DEFAULT_BLUR_FILL, enabled) }
    }

    fun setP3InheritPrevious(enabled: Boolean) {
        domainPrefs.edit { putBoolean(KEY_P3_INHERIT_PREVIOUS, enabled) }
    }

    fun getDisabledImages(): Set<String> = synchronized(disabledImagesLock) {
        domainPrefs.getStringSet(KEY_DISABLED_IMAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setImageEnabled(imageId: String, enabled: Boolean) {
        synchronized(disabledImagesLock) {
            val current = getDisabledImages().toMutableSet()
            if (enabled) {
                current.remove(imageId)
            } else {
                current.add(imageId)
            }
            domainPrefs.edit { putStringSet(KEY_DISABLED_IMAGES, current) }
        }
    }

    fun clearDisabledImages() {
        synchronized(disabledImagesLock) {
            domainPrefs.edit { remove(KEY_DISABLED_IMAGES) }
        }
    }

    private fun getDefaultImageCycleSettings() = ImageCycleSettings(
        enabled = DEFAULT_IMAGE_CYCLE_ENABLED,
        intervalMillis = DEFAULT_TRANSITION_INTERVAL_MILLIS,
        cycleOnUnlock = DEFAULT_CYCLE_IMAGE_ON_UNLOCK,
    )

    fun getEffectTransitionDurationMillis(): Long =
        prefs.getLong(KEY_EFFECT_TRANSITION_DURATION, DEFAULT_EFFECT_TRANSITION_DURATION_MILLIS)

    fun setEffectTransitionDurationMillis(durationMillis: Long) {
        prefs.edit {
            putLong(KEY_EFFECT_TRANSITION_DURATION, durationMillis.coerceIn(0L, 3000L))
        }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        if (domainPrefs === prefs) {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            return
        }
        synchronized(listenerRegistrations) {
            if (listener in listenerRegistrations) return
            val registration = ListenerRegistration(
                global = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
                    if (key == null || key !in DOMAIN_KEYS) listener.onSharedPreferenceChanged(source, key)
                },
                domain = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
                    if (key == null || key in DOMAIN_KEYS) listener.onSharedPreferenceChanged(source, key)
                },
            )
            listenerRegistrations[listener] = registration
            prefs.registerOnSharedPreferenceChangeListener(registration.global)
            domainPrefs.registerOnSharedPreferenceChangeListener(registration.domain)
        }
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        if (domainPrefs === prefs) {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            return
        }
        synchronized(listenerRegistrations) {
            val registration = listenerRegistrations.remove(listener) ?: return
            prefs.unregisterOnSharedPreferenceChangeListener(registration.global)
            domainPrefs.unregisterOnSharedPreferenceChangeListener(registration.domain)
        }
    }

    fun isBlurOnScreenLockEnabled(): Boolean =
        prefs.getBoolean(KEY_BLUR_ON_SCREEN_LOCK, false)

    fun setBlurOnScreenLock(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_BLUR_ON_SCREEN_LOCK, enabled)
        }
    }

    fun isBlurTimeoutEnabled(): Boolean =
        prefs.getBoolean(KEY_BLUR_TIMEOUT_ENABLED, false)

    fun setBlurTimeoutEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_BLUR_TIMEOUT_ENABLED, enabled)
        }
    }

    fun isUnblurOnUnlockEnabled(): Boolean =
        prefs.getBoolean(KEY_UNBLUR_ON_UNLOCK, false)

    fun setUnblurOnUnlockEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_UNBLUR_ON_UNLOCK, enabled)
        }
    }

    fun getBlurTimeoutMillis(): Long =
        prefs.getLong(KEY_BLUR_TIMEOUT_MILLIS, DEFAULT_BLUR_TIMEOUT_MILLIS)
            .coerceIn(MIN_BLUR_TIMEOUT_MILLIS, MAX_BLUR_TIMEOUT_MILLIS)

    fun setBlurTimeoutMillis(durationMillis: Long) {
        val clamped = durationMillis.coerceIn(MIN_BLUR_TIMEOUT_MILLIS, MAX_BLUR_TIMEOUT_MILLIS)
        prefs.edit {
            putLong(KEY_BLUR_TIMEOUT_MILLIS, clamped)
        }
    }

    fun getLastSelectedTab(): Int =
        prefs.getInt(KEY_LAST_SELECTED_TAB, 0)

    fun setLastSelectedTab(tabIndex: Int) {
        prefs.edit {
            putInt(KEY_LAST_SELECTED_TAB, tabIndex)
        }
    }

    fun getGestureSettings(): GestureSettings {
        val json = prefs.getString(KEY_GESTURE_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<GestureSettings>(json)
            } catch (e: Exception) {
                GestureSettings()
            }
        } else {
            GestureSettings()
        }
    }

    fun setGestureSettings(settings: GestureSettings) {
        prefs.edit {
            putString(KEY_GESTURE_SETTINGS, Json.encodeToString(settings))
        }
    }

    fun setGestureAction(event: TapGesture, action: GestureAction) {
        val current = getGestureSettings()
        val updated = when (event) {
            TapGesture.TRIPLE_TAP -> current.copy(tripleTapAction = action)
            TapGesture.TWO_FINGER_DOUBLE_TAP -> current.copy(twoFingerDoubleTapAction = action)
            TapGesture.THREE_FINGER_DOUBLE_TAP -> current.copy(threeFingerDoubleTapAction = action)
            TapGesture.NONE -> return
        }
        setGestureSettings(updated)
    }
}
