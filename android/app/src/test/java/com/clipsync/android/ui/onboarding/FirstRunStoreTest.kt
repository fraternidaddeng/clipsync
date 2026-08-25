package com.clipsync.android.ui.onboarding

import com.clipsync.android.pairing.FakeKeyValueStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunStoreTest {
    private val keyValues = FakeKeyValueStore()
    private val store = FirstRunStore(keyValues)

    @Test
    fun `fresh unpaired install shows the introduction until it is dismissed`() {
        assertTrue(store.shouldShowOnboarding(alreadyPaired = false))
        // Asking alone never marks it seen — only an explicit dismissal does.
        assertTrue(store.shouldShowOnboarding(alreadyPaired = false))

        store.markOnboardingSeen()
        assertFalse(store.shouldShowOnboarding(alreadyPaired = false))
    }

    @Test
    fun `already paired install is marked seen instead of interrupted`() {
        assertFalse(store.shouldShowOnboarding(alreadyPaired = true))
        // The decision sticks even if the pairing is forgotten later.
        assertFalse(store.shouldShowOnboarding(alreadyPaired = false))
    }

    @Test
    fun `the seen flag persists through the same key-value store`() {
        store.markOnboardingSeen()
        assertFalse(FirstRunStore(keyValues).shouldShowOnboarding(alreadyPaired = false))
    }
}
