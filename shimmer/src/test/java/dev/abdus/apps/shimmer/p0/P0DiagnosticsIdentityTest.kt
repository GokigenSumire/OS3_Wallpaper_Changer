package dev.abdus.apps.shimmer.p0

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P9-DIAG-TARGET-01：诊断身份必须来自实际应用清单，不硬编码，也不混用设备 API。
 *
 * 此处只替换诊断对象持有的 Context，避免为纯摘要测试启动写入线程或修改持久偏好。
 * 不验证真实壁纸服务启动、手机安装身份或 GLES 生命周期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class P0DiagnosticsIdentityTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val appField = P0Diagnostics::class.java.getDeclaredField("app").apply { isAccessible = true }
    private var previousContext: Context? = null
    private var previousTarget = 0

    @Before
    fun preserveContext() {
        previousContext = appField.get(P0Diagnostics) as Context?
        previousTarget = application.applicationInfo.targetSdkVersion
    }

    @After
    fun restoreContext() {
        application.applicationInfo.targetSdkVersion = previousTarget
        appField.set(P0Diagnostics, previousContext)
    }

    @Test
    fun uninitializedSummaryShowsUnknownWithoutThrowing() {
        appField.set(P0Diagnostics, null)

        val summary = P0Diagnostics.summary()

        assertEquals(identityPrefix() + " target=UNKNOWN", summary.lineSequence().first())
        assertFalse(summary.lineSequence().first().contains("target=36"))
        assertTrue(summary.contains("session=${P0Diagnostics.session}"))
    }

    @Test
    fun summaryUsesApplicationTargetRatherThanRuntimeSdk() {
        application.applicationInfo.targetSdkVersion = 37
        appField.set(P0Diagnostics, application)

        val summary = P0Diagnostics.summary()

        assertEquals(identityPrefix() + " target=37", summary.lineSequence().first())
        assertEquals(34, android.os.Build.VERSION.SDK_INT)
        assertFalse(summary.lineSequence().first().contains("target=36"))
    }

    @Test
    fun targetValueIsNotHardCodedToTheCurrentCandidateEither() {
        application.applicationInfo.targetSdkVersion = 35
        appField.set(P0Diagnostics, application)

        assertEquals(identityPrefix() + " target=35", P0Diagnostics.summary().lineSequence().first())
    }

    @Test
    fun exportedSummaryCarriesTheSameActualTarget() {
        application.applicationInfo.targetSdkVersion = 37
        appField.set(P0Diagnostics, application)

        assertEquals(identityPrefix() + " target=37", P0Diagnostics.export().lineSequence().first())
    }

    private fun identityPrefix(): String =
        "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
