package dev.abdus.apps.shimmer.p2

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.abdus.apps.shimmer.database.P1ImageRecordEntity
import dev.abdus.apps.shimmer.database.P1SourceEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P2SourceRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = ShimmerDatabase.getInstance(context)
    private val store = PlaybackSnapshotStore(context)
    private var controller: ActivityController<P2PlaybackActivity>? = null

    @After
    fun closeActivityAndDatabase() {
        controller?.pause()?.stop()?.destroy()
        database.close()
        ShimmerDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    @Test
    fun separatelyAddedDirectoriesRemainAtTheEndInDiscoveryOrderAfterRefreshAndRestart() {
        seed("z-old", "old1", "old2")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = listOf(uri("old2"), uri("old1"))))
        openActivity()
        awaitNames("old2", "old1")

        seed("m-first", "first1", "first2")
        click("重新载入已添加且启用的来源")
        awaitNames("old2", "old1", "first1", "first2")

        // The later source sorts before the earlier source in the DAO's stable ID order.
        seed("a-second", "second1", "second2")
        click("重新载入已添加且启用的来源")
        awaitNames("old2", "old1", "first1", "first2", "second1", "second2")

        controller!!.pause().stop().destroy()
        controller = null
        openActivity()
        awaitNames("old2", "old1", "first1", "first2", "second1", "second2")
    }

    @Test
    fun changingOrderFromOpenPageRetainsLatestWallpaperHistory() {
        seed("source", "one", "two", "three")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL,
            queueIds = listOf(uri("one"), uri("two"), uri("three")),
            historyIds = listOf(uri("one")), historyCursor = 0, currentId = uri("one")))
        openActivity()
        awaitNames("one", "two", "three")

        // A running wallpaper commits another image while this page remains open.
        val latest = store.load()!!.copy(historyIds = listOf(uri("one"), uri("two")),
            historyCursor = 1, currentId = uri("two"), queueIndex = 1)
        store.save(latest)
        click("自然降序")
        awaitCondition { store.load()!!.order == PlaybackOrder.NATURAL_DESC }

        assertEquals(latest.historyIds, store.load()!!.historyIds)
        assertEquals(latest.historyCursor, store.load()!!.historyCursor)
        assertEquals(latest.currentId, store.load()!!.currentId)
        assertEquals(PlaybackOrder.NATURAL_DESC, store.load()!!.order)
    }

    @Test
    fun directoriesAddedBeforeOneRefreshRemainInTheirAdditionOrder() {
        seed("z-first", "first1", "first2")
        seed("a-second", "second1", "second2")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = emptyList()))
        openActivity()

        awaitNames("first1", "first2", "second1", "second2")
    }

    @Test
    fun sourceGroupsShowStablePositionAndTypeForVisualReordering() {
        seed("first", "first1")
        seed("second", "second1")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = emptyList()))
        openActivity()
        awaitNames("first1", "second1")

        val headers = views(controller!!.get().window.decorView)
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
            .filter { it.contains("组") && it.contains("文件夹目录") }
            .toList()
        assertEquals(2, headers.size)
        assertTrue(headers[0].contains("第 1 组/共 2 组"))
        assertTrue(headers[1].contains("第 2 组/共 2 组"))
    }

    @Test
    fun movingSplitGroupKeepsOtherInterleavedItemsAndLatestHistory() {
        seed("a", "a1", "a2")
        seed("b", "b1", "b2")
        seed("c", "c1", "c2")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL,
            queueIds = listOf("b1", "c1", "b2", "a1", "c2", "a2").map(::uri)))
        openActivity()
        awaitNames("b1", "c1", "b2", "a1", "c2", "a2")
        val latest = store.load()!!.copy(currentId = uri("c1"),
            historyIds = listOf(uri("b1"), uri("c1")), historyCursor = 1)
        store.save(latest)
        clickGroup("a", "组上移")
        awaitNames("b1", "a1", "a2", "c1", "b2", "c2")
        assertEquals(latest.historyIds, store.load()!!.historyIds)
        assertEquals(latest.historyCursor, store.load()!!.historyCursor)
        assertEquals(latest.currentId, store.load()!!.currentId)
    }

    @Test
    fun singleImageCanStillCrossGroupThenWholeGroupCanMoveDown() {
        seed("a", "a1", "a2")
        seed("b", "b1", "b2")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL,
            queueIds = listOf("a1", "a2", "b1", "b2").map(::uri)))
        openActivity()
        awaitNames("a1", "a2", "b1", "b2")
        val image = views(controller!!.get().window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString().startsWith("2. a2.png\n") }
        views(image.parent as View).filterIsInstance<Button>()
            .first { it.text.toString() == "下移" }.performClick()
        awaitNames("a1", "b1", "a2", "b2")
        clickGroup("a", "组下移")
        awaitNames("b1", "b2", "a1", "a2")
    }

    @Test
    fun oneTapOpensExactlyOneDirectoryLevelAndCollapsingTheParentHidesItsSubtree() {
        // The v14 report: adding 1/ listed 1-1/ and 1-2/ as siblings instead of collapsing under 1/.
        seedTree("dir", "top.png", "1/1-1/a.png", "1/1-2/b.png")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = emptyList()))
        openActivity()
        awaitCondition { subgroupHeaders().size == 1 }

        // The v15 report: the added folder itself must collapse too, hiding every row inside it.
        assertEquals(listOf("▶ 来源目录：dir/ · 共 3 张 · 点击展开"), headerTexts())
        assertEquals(emptyList<String>(), listedNames())

        // Opening the source folder shows its own images and one collapsed header per child.
        subgroupHeaders().single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf("▼ 来源目录：dir/ · 共 3 张 · 点击折叠", "▶ 子目录：1/ · 共 2 张 · 点击展开"),
            headerTexts(),
        )
        assertEquals(listOf("top.png"), listedNames())

        // A single tap opens one level only: the child folders appear, their images stay hidden.
        subgroupHeaders()[1].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(
                "▼ 来源目录：dir/ · 共 3 张 · 点击折叠",
                "▼ 子目录：1/ · 共 2 张 · 点击折叠",
                "▶ 子目录：1-1/ · 共 1 张 · 点击展开",
                "▶ 子目录：1-2/ · 共 1 张 · 点击展开",
            ),
            headerTexts(),
        )
        assertEquals(listOf("top.png"), listedNames())

        subgroupHeaders()[2].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("top.png", "a.png"), listedNames())

        // Collapsing a parent hides the whole subtree, including the level opened inside it.
        subgroupHeaders()[1].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf("▼ 来源目录：dir/ · 共 3 张 · 点击折叠", "▶ 子目录：1/ · 共 2 张 · 点击展开"),
            headerTexts(),
        )
        assertEquals(listOf("top.png"), listedNames())
        subgroupHeaders()[0].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("▶ 来源目录：dir/ · 共 3 张 · 点击展开"), headerTexts())
        assertEquals(emptyList<String>(), listedNames())
    }

    @Test
    fun addingAFolderWithOnlySubfoldersListsThatFolderAloneUntilTapped() {
        // TC-TREE-15 exactly: 1/ holds 1-1/ and 1-2/ and no images of its own.
        seedTree("1", "1-1/a.png", "1-1/深/c.png", "1-2/b.png")
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = emptyList()))
        openActivity()
        awaitCondition { subgroupHeaders().size == 1 }
        assertEquals(listOf("▶ 来源目录：1/ · 共 3 张 · 点击展开"), headerTexts())

        subgroupHeaders().single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(
                "▼ 来源目录：1/ · 共 3 张 · 点击折叠",
                "▶ 子目录：1-1/ · 共 2 张 · 点击展开",
                "▶ 子目录：1-2/ · 共 1 张 · 点击展开",
            ),
            headerTexts(),
        )
        assertEquals(emptyList<String>(), listedNames())
    }

    @Test
    fun largeSubgroupCreatesRowsInPagesOfFifty() {
        val names = (1..120).map { "p%03d".format(it) }
        seedTree("big", *names.map { "图/$it.png" }.toTypedArray())
        store.save(PlaybackSnapshot(order = PlaybackOrder.MANUAL, queueIds = emptyList()))
        openActivity()
        awaitCondition { subgroupHeaders().size == 1 }
        subgroupHeaders().single().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        subgroupHeaders()[1].performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(names.take(50).map { "$it.png" }, listedNames())
        click("显示后续 50 张（剩余 70 张）")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(names.take(100).map { "$it.png" }, listedNames())
        click("显示后续 20 张（剩余 20 张）")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(names.map { "$it.png" }, listedNames())
        assertTrue(views(controller!!.get().window.decorView).filterIsInstance<Button>()
            .none { it.text.startsWith("显示后续") })
    }

    private fun clickGroup(source: String, action: String) {
        val header = views(controller!!.get().window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString().contains("文件夹目录") && it.text.toString().endsWith("\n/$source") }
        views(header.parent as View).filterIsInstance<Button>()
            .first { it.text.toString() == action }.performClick()
    }

    private fun seed(source: String, vararg names: String) =
        seedTree(source, *names.map { "$it.png" }.toTypedArray())

    /** Registers a source whose images keep the given relative paths, folders included. */
    private fun seedTree(source: String, vararg paths: String) = runBlocking {
        val dao = database.p1SourceDao()
        dao.registerSource(P1SourceEntity(source, "file:///$source", "DIRECTORY_TREE"))
        dao.commitScan(source, paths.map { path ->
            val name = path.substringAfterLast('/')
            P1ImageRecordEntity(
                id = name.removeSuffix(".png"),
                uri = "file:///$name",
                displayName = name,
                relativePath = path,
            )
        }, "APP_OWNED", "2026-09-25T00:00:00Z")
    }

    private fun openActivity() {
        controller = Robolectric.buildActivity(P2PlaybackActivity::class.java).setup()
    }

    private fun click(label: String) {
        views(controller!!.get().window.decorView).filterIsInstance<Button>()
            .first { it.text.toString() == label }.performClick()
    }

    private fun awaitNames(vararg names: String) {
        val expected = names.map { "$it.png" }
        val timeout = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            expandAllSubgroups()
            if (listedNames() == expected) return
            Thread.sleep(10)
        } while (System.nanoTime() < timeout)
        assertEquals(expected, listedNames())
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val timeout = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < timeout)
        assertTrue(condition())
    }

    /** Directory levels start collapsed; open each one the way a user would tap it. */
    private fun expandAllSubgroups() {
        repeat(100) {
            val collapsed = subgroupHeaders().firstOrNull { it.text.startsWith("▶") } ?: return
            collapsed.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Folder rows are real buttons, so one tap opens a level instead of selecting its text. */
    private fun subgroupHeaders(): List<Button> = views(controller!!.get().window.decorView)
        .filterIsInstance<Button>()
        .filter { it.text.contains("子目录：") || it.text.contains("来源目录：") }
        .toList()

    private fun headerTexts(): List<String> = subgroupHeaders().map { it.text.toString() }

    private fun listedNames(): List<String> = views(controller!!.get().window.decorView)
        .filterIsInstance<TextView>()
        .mapNotNull { Regex("\\d+\\. (.+)\\n.*").matchEntire(it.text.toString())?.groupValues?.get(1) }
        .toList()

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun uri(name: String): String = "file:///$name.png"
}
