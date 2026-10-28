package dev.abdus.apps.shimmer.gl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadIdentityTest {
    private class CollidingValue {
        override fun hashCode() = 42
        override fun equals(other: Any?) = other is CollidingValue
    }
    @Test fun unrecordedIdentityNeverReusesEvenReadyTextures() {
        val gate = ImageUploadIdentity()
        assertFalse(gate.canReuse(Any(), Any(), emptyList(), null, true))
    }
    @Test fun onlyTheExactRecordedOwnerAndLayersCanReuse() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any(); val blur = Any(); val fill = Any()
        gate.record(owner, original, listOf(blur), fill)
        assertTrue(gate.canReuse(owner, original, listOf(blur), fill, true))
    }
    @Test fun collidingAndEqualOwnersStillRequireANewUpload() {
        val gate = ImageUploadIdentity(); val a = CollidingValue(); val b = CollidingValue(); val original = Any()
        assertTrue(a == b && a.hashCode() == b.hashCode())
        gate.record(a, original, emptyList(), null)
        assertFalse(gate.canReuse(b, original, emptyList(), null, true))
    }
    @Test fun emptyTexturesRequireReuploadEvenForTheSameOwner() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any()
        gate.record(owner, original, emptyList(), null)
        assertFalse(gate.canReuse(owner, original, emptyList(), null, false))
    }
    @Test fun changedForegroundReferenceCannotReuseEvenIfItIsEqualAndHasTheSameHash() {
        val gate = ImageUploadIdentity(); val owner = Any(); val a = CollidingValue(); val b = CollidingValue()
        gate.record(owner, a, emptyList(), null)
        assertFalse(gate.canReuse(owner, b, emptyList(), null, true))
    }
    @Test fun changedBlurReferenceCannotReuseEvenIfItIsEqualAndHasTheSameHash() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any(); val a = CollidingValue(); val b = CollidingValue()
        gate.record(owner, original, listOf(a), null)
        assertFalse(gate.canReuse(owner, original, listOf(b), null, true))
    }
    @Test fun blurOrderAndCountArePartOfExactUploadIdentity() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any(); val a = Any(); val b = Any()
        gate.record(owner, original, listOf(a, b), null)
        assertFalse(gate.canReuse(owner, original, listOf(b, a), null, true))
        assertFalse(gate.canReuse(owner, original, listOf(a), null, true))
    }
    @Test fun externallyChangedMutableBlurListDoesNotMutateTheRecordedIdentity() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any(); val a = Any(); val b = Any()
        val input = mutableListOf<Any>(a)
        gate.record(owner, original, input, null)
        input[0] = b
        assertFalse(gate.canReuse(owner, original, input, null, true))
        assertTrue(gate.canReuse(owner, original, listOf(a), null, true))
    }
    @Test fun changedOrRemovedBackgroundRequiresANewUpload() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any(); val fill = Any()
        gate.record(owner, original, emptyList(), fill)
        assertFalse(gate.canReuse(owner, original, emptyList(), Any(), true))
        assertFalse(gate.canReuse(owner, original, emptyList(), null, true))
    }
    @Test fun addingBackgroundToAPreviousEmptyFillCannotReuse() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any()
        gate.record(owner, original, emptyList(), null)
        assertFalse(gate.canReuse(owner, original, emptyList(), Any(), true))
    }
    @Test fun clearBeforeAFailedUploadPreventsStaleReuseOnRetry() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any()
        gate.record(owner, original, emptyList(), null)
        gate.clear() // Renderer starts an upload; failure never reaches record.
        assertFalse(gate.canReuse(owner, original, emptyList(), null, true))
        gate.record(owner, original, emptyList(), null)
        assertTrue(gate.canReuse(owner, original, emptyList(), null, true))
    }
    @Test fun releaseClearInvalidatesAndDropsAllRecordedLayerReferences() {
        val gate = ImageUploadIdentity(); val owner = Any(); val original = Any(); val blur = Any(); val fill = Any()
        gate.record(owner, original, listOf(blur), fill); gate.clear(); gate.clear()
        assertFalse(gate.canReuse(owner, original, listOf(blur), fill, true))
    }
    @Test fun successfulNewOwnerReplacesOldRatherThanKeepingAnUnboundedHistory() {
        val gate = ImageUploadIdentity(); val a = Any(); val b = Any(); val original = Any()
        gate.record(a, original, emptyList(), null); gate.record(b, original, emptyList(), null)
        assertFalse(gate.canReuse(a, original, emptyList(), null, true))
        assertTrue(gate.canReuse(b, original, emptyList(), null, true))
    }
}
