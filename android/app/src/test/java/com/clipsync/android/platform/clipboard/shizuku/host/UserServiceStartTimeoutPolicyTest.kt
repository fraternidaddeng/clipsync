package com.clipsync.android.platform.clipboard.shizuku.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserServiceStartTimeoutPolicyTest {
    @Test
    fun `still starting with no binder is a timeout`() {
        assertTrue(UserServiceStartTimeoutPolicy.shouldDestroy(starting = true, binderAttached = false))
    }

    @Test
    fun `attach that cleared starting is not destroyed`() {
        assertFalse(UserServiceStartTimeoutPolicy.shouldDestroy(starting = false, binderAttached = true))
    }

    @Test
    fun `attach that set binder before starting flipped is not destroyed`() {
        assertFalse(UserServiceStartTimeoutPolicy.shouldDestroy(starting = true, binderAttached = true))
    }

    @Test
    fun `idle slot is not destroyed`() {
        assertFalse(UserServiceStartTimeoutPolicy.shouldDestroy(starting = false, binderAttached = false))
    }
}
