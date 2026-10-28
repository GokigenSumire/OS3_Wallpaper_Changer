package dev.abdus.apps.shimmer.ui.playback

import dev.abdus.apps.shimmer.p2.PlaybackGroupKey
import dev.abdus.apps.shimmer.p2.PlaybackItem
import dev.abdus.apps.shimmer.p2.PlaybackOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHG-P9-BROWSE-20261009：首次进入定位，随后切页保留浏览位置。
 *
 * 用户在版本 41 反馈中改变了“每次切页定位”的契约。以下改写对应旧断言，保留显式定位与调序测试。
 * 这里只验证定位决策与展开树；Compose 的实际偏移恢复由接线检查和目标机测试验证。
 */
class PlaybackEntryFocusTest {
    private fun item(id: String, path: String, source: String, kind: String = "DIRECTORY_TREE") =
        PlaybackItem(id, id.substringAfterLast('/'), path, source, sourceKind = kind, sourceRootUri = "root-$source")

    private val deep = (1..130).map { item("d$it", "1/1-1/d$it.jpg", "A") } + item("top", "top.jpg", "A")
    private val batch = listOf(
        item("m1", "m1.jpg", "M", kind = "MULTIPLE_DOCUMENTS"),
        item("m2", "m2.jpg", "M", kind = "MULTIPLE_DOCUMENTS"),
    )
    private val groupA = PlaybackGroupKey("DIRECTORY_TREE", "A")

    /** 定位后的行键必须真的出现在展开后的可见行里，界面才能滚过去。 */
    private fun assertRowVisible(model: QueueModel, focus: QueueFocus) {
        val key = focus.rowKey!!
        assertTrue("$key 不在可见行里", buildQueueRows(model, focus.view).any { it.key == key })
    }

    @Test
    fun firstEntryWithNothingPendingLocatesTheCurrentImage() {
        assertEquals(FocusTarget.Current, entryFocus(null, hasEntered = false))
    }

    @Test
    fun aPendingMoveResultWinsOverTheEntryDefault() {
        // 移动刚写完、界面重建时，仍滚到刚移动的那一张或那一组，而不是跳回当前图片
        val item = FocusTarget.Item("d3")
        val group = FocusTarget.Group(groupA)
        for (entered in listOf(false, true)) {
            assertSame(item, entryFocus(item, entered))
            assertSame(group, entryFocus(group, entered))
            assertSame(FocusTarget.Current, entryFocus(FocusTarget.Current, entered))
        }
    }

    @Test
    fun everyOrderScrollsToTheCurrentImageOnFirstEntry() {
        // 首次进入仍对所有顺序统一定位；再次进入不隐式定位。
        for (order in PlaybackOrder.values()) {
            val model = buildQueueModel(deep, order, currentId = "d120")
            val focus = QueueViewState().focus(model, entryFocus(null, hasEntered = false)!!)
            assertEquals("顺序 $order", imageRowKey("d120"), focus.rowKey)
            assertRowVisible(model, focus)
        }
    }

    @Test
    fun reEnteringKeepsExpandedPagesAndSelectionWithoutAScrollRequest() {
        // 原 reEnteringWithAFreshListStillScrolls 与新契约相反，改为不产生第二次定位。
        val model = buildQueueModel(deep, PlaybackOrder.RANDOM, currentId = "d120")
        val first = QueueViewState().focus(model, entryFocus(null, hasEntered = false)!!)
        val browsing = first.view.copy(selectedItemId = "d10", movedItemId = "d77")
        val target = entryFocus(null, hasEntered = true)
        val again = target?.let { browsing.focus(model, it) } ?: QueueFocus(browsing, null)
        assertNull(again.rowKey)
        assertSame(browsing, again.view)
        assertEquals(first.view.expanded, again.view.expanded)
        assertEquals(first.view.visibleRows, again.view.visibleRows)
    }

    @Test
    fun repeatedVisitsDoNotFocusEvenWhenTheCurrentImageChanged() {
        for (order in PlaybackOrder.values()) {
            val model = buildQueueModel(deep, order, currentId = "top")
            var view = QueueViewState().focus(model, FocusTarget.Item("d120")).view
            repeat(10) {
                val target = entryFocus(null, hasEntered = true)
                assertNull(target)
                val before = view
                if (target != null) view = view.focus(model, target).view
                assertSame(before, view)
            }
        }
    }

    @Test
    fun anExplicitLocateStillRevealsTheNewCurrentAfterReEntry() {
        val model = buildQueueModel(deep, PlaybackOrder.RANDOM, currentId = "top")
        val view = QueueViewState().focus(model, FocusTarget.Item("d120")).view
        assertNull(entryFocus(null, hasEntered = true))
        val focus = view.focus(model, FocusTarget.Current)
        assertEquals(imageRowKey("top"), focus.rowKey)
        assertRowVisible(model, focus)
    }

    @Test
    fun aBatchImageIsRevealedInsideItsGroupLevel() {
        val model = buildQueueModel(deep + batch, PlaybackOrder.RANDOM, currentId = "m2")
        val focus = QueueViewState().focus(model, FocusTarget.Current)
        assertEquals(imageRowKey("m2"), focus.rowKey)
        assertRowVisible(model, focus)
    }

    @Test
    fun noCurrentImageMeansNoScrollAndNoChange() {
        // 还未开始播放，或当前图片已被停用：不滚动，"定位当前"据此提示
        val view = QueueViewState()
        val notStarted = buildQueueModel(deep, PlaybackOrder.RANDOM, currentId = null)
        val disabled = buildQueueModel(deep, PlaybackOrder.RANDOM, currentId = "gone")
        for (model in listOf(notStarted, disabled)) {
            val focus = view.focus(model, FocusTarget.Current)
            assertNull(focus.rowKey)
            assertEquals(view, focus.view)
        }
    }

    @Test
    fun aMovedImageThatLeftTheListIsNotScrolledTo() {
        val model = buildQueueModel(deep, PlaybackOrder.MANUAL, currentId = null)
        val focus = QueueViewState().focus(model, FocusTarget.Item("gone"))
        assertNull(focus.rowKey)
        assertEquals(QueueViewState(), focus.view)
    }

    @Test
    fun aMovedGroupScrollsToItsFirstHeader() {
        val single = item("s1", "s1.jpg", "S", kind = "SINGLE_DOCUMENT")
        val ordered = listOf(deep[0], single) + deep.drop(1)
        val model = buildQueueModel(ordered, PlaybackOrder.MANUAL, currentId = null)
        val focus = QueueViewState().focus(model, FocusTarget.Group(groupA))
        assertEquals(groupRowKey(model.blocks.first { it.group == groupA }), focus.rowKey)
        assertRowVisible(model, focus)
    }

    @Test
    fun aMovedImageIsRevealedEvenWhenItsLevelWasCollapsed() {
        val model = buildQueueModel(deep, PlaybackOrder.MANUAL, currentId = "top")
        val focus = QueueViewState().focus(model, FocusTarget.Item("d77"))
        assertEquals(imageRowKey("d77"), focus.rowKey)
        assertRowVisible(model, focus)
    }
}
