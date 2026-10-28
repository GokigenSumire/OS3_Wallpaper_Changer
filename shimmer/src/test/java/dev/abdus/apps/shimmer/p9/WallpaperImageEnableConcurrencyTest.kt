package dev.abdus.apps.shimmer.p9

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.WallpaperPreferences
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 同一进程多个偏好实例共享图片启停集合，读改写／清空使用同一把锁。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WallpaperImageEnableConcurrencyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val backing = context.getSharedPreferences("image-enable-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun preferences(store: SharedPreferences = backing) = WallpaperPreferences(store)
    private val uris = (0 until 256).map { "content://test/shared-image/$it" }

    private fun concurrent(store: SharedPreferences, work: (Int, WallpaperPreferences) -> Unit) {
        val workerIds = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8) { action ->
            Thread(action, "image-enable-worker-${workerIds.incrementAndGet()}")
        }
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map { worker ->
                executor.submit {
                    val instance = preferences(store)
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    work(worker, instance)
                }
            }
            assertTrue("各实例应进入并发起点", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { future ->
                try {
                    future.get(10, TimeUnit.SECONDS)
                } catch (timeout: TimeoutException) {
                    val workers = Thread.getAllStackTraces().filterKeys {
                        it.name.startsWith("image-enable-worker-")
                    }.entries.joinToString("\n") { (thread, stack) ->
                        "${thread.name} ${thread.state}\n${stack.joinToString("\n")}"
                    }
                    throw AssertionError("图片启停并发操作超过 10 秒，工作线程：\n$workers", timeout)
                }
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentDisablingDifferentUrisAcrossInstancesKeepsEveryWrite() {
        val store = StringSetMemoryPreferences()
        repeat(3) {
            preferences(store).clearDisabledImages()
            concurrent(store) { worker, instance ->
                uris.filterIndexed { index, _ -> index % 8 == worker }.forEach { uri ->
                    instance.setImageEnabled(uri, false)
                    Thread.yield()
                }
            }
            assertEquals(uris.toSet(), preferences(store).getDisabledImages())
        }
    }

    @Test
    fun concurrentEnablingDifferentUrisAcrossInstancesDoesNotRestoreAnotherStoppedItem() {
        val store = StringSetMemoryPreferences()
        val untouched = "content://test/kept-stopped"
        uris.forEach { preferences(store).setImageEnabled(it, false) }
        preferences(store).setImageEnabled(untouched, false)

        concurrent(store) { worker, instance ->
            uris.filterIndexed { index, _ -> index % 8 == worker }.forEach { uri ->
                instance.setImageEnabled(uri, true)
                Thread.yield()
            }
        }

        assertEquals(setOf(untouched), preferences(store).getDisabledImages())
    }

    @Test
    fun concurrentMixedWritesKeepUnrelatedEnableAndDisableDecisions() {
        val store = StringSetMemoryPreferences()
        val old = uris.take(128)
        val newlyStopped = uris.drop(128)
        old.forEach { preferences(store).setImageEnabled(it, false) }

        concurrent(store) { worker, instance ->
            uris.filterIndexed { index, _ -> index % 8 == worker }.forEach { uri ->
                instance.setImageEnabled(uri, uri in old)
                Thread.yield()
            }
        }

        assertEquals(newlyStopped.toSet(), preferences(store).getDisabledImages())
    }

    @Test
    fun sharedUriIsOneGlobalStateAndRepeatedRequestsAreIdempotent() {
        val firstSource = preferences()
        val secondSource = preferences()
        val shared = uris.first()

        repeat(3) { firstSource.setImageEnabled(shared, false) }
        assertEquals(setOf(shared), secondSource.getDisabledImages())
        repeat(3) { secondSource.setImageEnabled(shared, true) }
        assertTrue(firstSource.getDisabledImages().isEmpty())
    }

    @Test
    fun returnedSetIsACopyAndNewInstancesReadTheSavedState() {
        val first = preferences()
        first.setImageEnabled(uris[0], false)
        val copy = first.getDisabledImages().toMutableSet()
        copy.clear()

        assertEquals(setOf(uris[0]), preferences().getDisabledImages())
        first.setImageEnabled(uris[1], false)
        assertEquals(setOf(uris[0], uris[1]), preferences().getDisabledImages())
    }

    @Test
    fun clearAndIndividualWritesWaitForTheSameCrossInstanceLock() {
        val lock = requireNotNull(WallpaperPreferences::class.java.getDeclaredField("disabledImagesLock").apply {
            isAccessible = true
        }.get(null))
        val executor = Executors.newSingleThreadExecutor()
        try {
            listOf<() -> Unit>(
                { preferences().setImageEnabled(uris[0], false) },
                { preferences().clearDisabledImages() },
            ).forEach { action ->
                val entered = CountDownLatch(1)
                val future = synchronized(lock) {
                    val running = executor.submit {
                        entered.countDown()
                        action()
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    try {
                        running.get(100, TimeUnit.MILLISECONDS)
                        throw AssertionError("读改写与清空必须等共享锁释放")
                    } catch (_: TimeoutException) {
                        assertFalse(running.isDone)
                    }
                    running
                }
                future.get(5, TimeUnit.SECONDS)
            }
            assertTrue(preferences().getDisabledImages().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * 仅用于高次数读改写压力，不替代真实 SharedPreferences 或设备持久化验证。
     * Robolectric 4.14.1 的 apply 会额外 waitToFinish，每次强制宿主落盘；此夹具仅模拟
     * Android apply 的即时内存提交。单次读写线程安全，但读与写之间不持有夹具锁，
     * 因此不能替生产 disabledImagesLock 掩盖丢失更新。真实偏好多实例、复制与清空仍在上面验证。
     */
    private class StringSetMemoryPreferences : SharedPreferences {
        private val memoryLock = Any()
        private var disabled: Set<String>? = null

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            require(key == WallpaperPreferences.KEY_DISABLED_IMAGES)
            val result = synchronized(memoryLock) { (disabled ?: defValues)?.toMutableSet() }
            Thread.yield()
            return result
        }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private var changed = false
            private var replacement: Set<String>? = null

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                require(key == WallpaperPreferences.KEY_DISABLED_IMAGES)
                changed = true
                replacement = values?.toSet()
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                require(key == WallpaperPreferences.KEY_DISABLED_IMAGES)
                changed = true
                replacement = null
            }

            override fun clear(): SharedPreferences.Editor = apply {
                changed = true
                replacement = null
            }

            override fun apply() {
                if (changed) synchronized(memoryLock) { disabled = replacement?.toSet() }
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = unsupported()
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = unsupported()
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = unsupported()
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = unsupported()
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = unsupported()
        }

        override fun getAll(): MutableMap<String, *> = synchronized(memoryLock) {
            mutableMapOf<String, Any>().apply {
                disabled?.let { put(WallpaperPreferences.KEY_DISABLED_IMAGES, it.toMutableSet()) }
            }
        }

        override fun contains(key: String?): Boolean = synchronized(memoryLock) {
            key == WallpaperPreferences.KEY_DISABLED_IMAGES && disabled != null
        }

        override fun getString(key: String?, defValue: String?): String? = unsupported()
        override fun getInt(key: String?, defValue: Int): Int = unsupported()
        override fun getLong(key: String?, defValue: Long): Long = unsupported()
        override fun getFloat(key: String?, defValue: Float): Float = unsupported()
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = unsupported()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unsupported()
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = unsupported()

        private fun unsupported(): Nothing = error("该压力夹具只支持图片停用集合，不模拟其他偏好或磁盘")
    }
}
