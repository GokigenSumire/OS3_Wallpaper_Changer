package dev.abdus.apps.shimmer.p10.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LockPreviewResourcesTest {
    private class Pixels { var releases = 0 }
    private fun owner() = LockPreviewResources<Pixels> { it.releases++ }

    @Test fun queuedPixelsHaveOneExplicitOwnerAndCanOnlyBeTakenOnce() {
        val owner = owner()
        val pixels = Pixels()
        val ticket = owner.enqueue(pixels)!!
        assertSame(pixels, owner.take(ticket))
        assertNull(owner.take(ticket))
        assertEquals(0, pixels.releases)
    }

    @Test fun replacingUploadedImageReleasesOnlyPriorOwnedImage() {
        val owner = owner()
        val a = Pixels(); val b = Pixels()
        owner.uploaded(a)
        owner.uploaded(b)
        assertEquals(1, a.releases)
        assertEquals(0, b.releases)
        assertSame(b, owner.current())
    }

    @Test fun stoppedRendererClearsCurrentAndEveryUnconsumedUpload() {
        val owner = owner()
        val current = Pixels(); val a = Pixels(); val b = Pixels()
        owner.uploaded(current)
        val aTicket = owner.enqueue(a)!!
        val bTicket = owner.enqueue(b)!!
        owner.clearAfterRendererStopped()
        assertEquals(1, current.releases)
        assertEquals(1, a.releases)
        assertEquals(1, b.releases)
        assertNull(owner.take(aTicket))
        assertNull(owner.take(bTicket))
        assertNull(owner.current())
    }

    @Test fun clearingTwiceDoesNotRecycleTwice() {
        val owner = owner()
        val current = Pixels(); val queued = Pixels()
        owner.uploaded(current); owner.enqueue(queued)
        owner.clearAfterRendererStopped(); owner.clearAfterRendererStopped()
        assertEquals(1, current.releases)
        assertEquals(1, queued.releases)
    }

    @Test fun aResumeCanOwnNewPixelsAfterPauseButNotAfterClose() {
        val owner = owner()
        val a = Pixels(); val b = Pixels(); val late = Pixels()
        owner.uploaded(a)
        owner.clearAfterRendererStopped()
        val ticket = owner.enqueue(b)!!
        owner.uploaded(owner.take(ticket)!!)
        assertSame(b, owner.current())
        owner.closeAfterRendererStopped()
        assertNull(owner.enqueue(late))
        assertEquals(1, a.releases)
        assertEquals(1, b.releases)
        assertEquals(1, late.releases)
    }

    @Test fun lateTakenUploadAfterCloseIsReleasedInsteadOfBecomingCurrent() {
        val owner = owner()
        val late = Pixels()
        val ticket = owner.enqueue(late)!!
        val taken = owner.take(ticket)!!
        owner.closeAfterRendererStopped()
        owner.uploaded(taken)
        assertEquals(1, late.releases)
        assertNull(owner.current())
    }

    @Test fun sameCurrentInstanceIsNotReleasedWhileStillOwned() {
        val owner = owner()
        val a = Pixels()
        owner.uploaded(a); owner.uploaded(a)
        assertEquals(0, a.releases)
        owner.closeAfterRendererStopped(); owner.closeAfterRendererStopped()
        assertEquals(1, a.releases)
    }
}
