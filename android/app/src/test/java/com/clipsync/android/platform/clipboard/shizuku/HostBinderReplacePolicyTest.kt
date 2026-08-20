package com.clipsync.android.platform.clipboard.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBinderReplacePolicyTest {
    @Test
    fun `same living binder is never torn down`() {
        assertFalse(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = true,
                currentAlive = true,
                incomingIsCurrent = true,
                granted = false,
            ),
        )
        assertFalse(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = true,
                currentAlive = true,
                incomingIsCurrent = true,
                granted = true,
            ),
        )
    }

    @Test
    fun `granted session is not replaced by a different binder`() {
        assertFalse(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = true,
                currentAlive = true,
                incomingIsCurrent = false,
                granted = true,
            ),
        )
    }

    @Test
    fun `unauthorized different living binder may replace the cache`() {
        assertTrue(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = true,
                currentAlive = true,
                incomingIsCurrent = false,
                granted = false,
            ),
        )
    }

    @Test
    fun `force reattach drops an unauthorized same binder so attach can run again`() {
        assertTrue(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = true,
                currentAlive = true,
                incomingIsCurrent = true,
                granted = false,
                forceReattach = true,
            ),
        )
        assertFalse(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = true,
                currentAlive = true,
                incomingIsCurrent = true,
                granted = true,
                forceReattach = true,
            ),
        )
    }

    @Test
    fun `dead current or dead incoming is left to the provider attach path`() {
        assertFalse(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = true,
                currentAlive = false,
                incomingIsCurrent = false,
                granted = false,
            ),
        )
        assertFalse(
            HostBinderReplacePolicy.shouldTearDownForIncoming(
                incomingAlive = false,
                currentAlive = true,
                incomingIsCurrent = false,
                granted = false,
            ),
        )
    }
}
