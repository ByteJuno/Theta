package io.github.bytejuno.thetacore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostUiLifetimeTest {
    @Test fun disposalAfterAdmissionBeforeRecompositionCancelsCurrentHandle() {
        val lifetime = HostInvocationLifetime()
        var cancelled = 0
        // The initial composition's disposal closure captures the holder, not a null call.
        val initialDispose = { lifetime.dispose() }
        lifetime.adopt { cancelled++ }
        initialDispose()
        initialDispose()
        assertEquals(1, cancelled)
    }

    @Test fun admissionCompletingAfterDisposalIsCancelledImmediately() {
        val lifetime = HostInvocationLifetime()
        var cancelled = 0
        lifetime.dispose()
        lifetime.adopt { cancelled++ }
        assertEquals(1, cancelled)
    }

    @Test fun resumedActivityRestoresItsPageBeforePluginExecution() {
        val attribution = HostPageAttribution()
        var journal: String? = null
        fun lease() = HostPageLease(attribution, { journal = it; true }, { journal = null })
        val a = lease()
        val b = lease()
        a.resume()
        assertTrue(a.beforeRender("plugin.a"))
        a.pause()
        b.resume()
        assertTrue(b.beforeRender(null))
        assertEquals(null, journal)
        b.pause()
        a.resume()
        // Restoration happens in the Activity callback, not a post-composition effect.
        assertEquals("plugin.a", journal)
        assertTrue(a.beforeRender("plugin.a"))
        assertEquals("plugin.a", journal)
    }

    @Test fun stoppedOrSupersededActivityCannotClearCurrentAttribution() {
        val attribution = HostPageAttribution()
        var journal: String? = null
        fun lease() = HostPageLease(attribution, { journal = it; true }, { journal = null })
        val a = lease()
        val b = lease()
        a.resume()
        a.beforeRender("plugin.a")
        b.resume()
        b.beforeRender("plugin.b")
        assertFalse(a.beforeRender(null))
        a.pause() // Delayed pause/destroy callback cannot release B's token.
        assertEquals("plugin.b", journal)
        assertTrue(b.ownsPage)
        assertFalse(a.beforeRender("plugin.a"))
        assertEquals("plugin.b", journal)
    }

    @Test fun listAndRevocationClearOnlyTheCurrentOwnersJournal() {
        val attribution = HostPageAttribution()
        var journal: String? = null
        val owner = HostPageLease(attribution, { journal = it; true }, { journal = null })
        owner.resume()
        owner.beforeRender("plugin.a")
        assertTrue(owner.beforeRender(null))
        assertEquals(null, journal)
        owner.pause()
        assertFalse(owner.beforeRender("plugin.a"))
    }

    @Test fun repeatedPageCompositionJournalsOnlyOncePerOwnershipEpoch() {
        val attribution = HostPageAttribution()
        var writes = 0
        val owner = HostPageLease(attribution, { writes++; true }, {})
        owner.resume()
        repeat(100) { assertTrue(owner.beforeRender("plugin.a")) }
        assertEquals(1, writes)
        owner.pause()
        owner.resume()
        assertEquals(2, writes) // Resume refreshes before any Content.
        repeat(100) { assertTrue(owner.beforeRender("plugin.a")) }
        assertEquals(2, writes)
    }

    @Test fun failedJournalInvalidatesCacheAndRetriesUntilSuccess() {
        val attribution = HostPageAttribution()
        var writes = 0
        var writable = true
        val owner = HostPageLease(attribution, { writes++; writable }, {})
        owner.resume()
        assertTrue(owner.beforeRender("plugin.a"))
        writable = false
        assertFalse(owner.beforeRender("plugin.b"))
        assertFalse(owner.beforeRender("plugin.b"))
        writable = true
        assertTrue(owner.beforeRender("plugin.a")) // Old A success was invalidated too.
        assertTrue(owner.beforeRender("plugin.a"))
        assertEquals(4, writes)
    }

    @Test fun ownershipRoundTripRefreshesCachedPageBeforeExecution() {
        val attribution = HostPageAttribution()
        val writes = mutableListOf<String>()
        fun lease() = HostPageLease(attribution, { writes += it; true }, {})
        val a = lease()
        val b = lease()
        a.resume()
        a.beforeRender("plugin.a")
        b.resume()
        b.beforeRender("plugin.b")
        a.resume() // Even without observing a paused composition, invalidate the cache.
        assertEquals(listOf("plugin.a", "plugin.b", "plugin.a"), writes)
        a.beforeRender("plugin.a")
        assertEquals(3, writes.size)
    }

    @Test fun failedJournalPreventsPageAdmissionAfterResume() {
        val attribution = HostPageAttribution()
        var writable = true
        val owner = HostPageLease(attribution, { writable }, {})
        owner.resume()
        assertTrue(owner.beforeRender("plugin.a"))
        owner.pause()
        writable = false
        owner.resume()
        assertFalse(owner.beforeRender("plugin.a"))
    }
}
