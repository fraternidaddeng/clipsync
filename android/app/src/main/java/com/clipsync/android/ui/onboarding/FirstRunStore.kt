package com.clipsync.android.ui.onboarding

import com.clipsync.android.pairing.KeyValueStore

/**
 * Remembers whether the first-run introduction has been shown. Pure decision
 * logic over a [KeyValueStore], so the once-only rule stays unit-testable
 * without Android.
 */
class FirstRunStore(private val keyValues: KeyValueStore) {

    /**
     * The introduction shows exactly once, and only to an install that has not
     * paired yet: an already-paired device (e.g. the app data survived an
     * upgrade) has walked past everything the introduction explains, so it is
     * marked seen instead of interrupted.
     */
    fun shouldShowOnboarding(alreadyPaired: Boolean): Boolean {
        if (keyValues.read(KEY_ONBOARDING_SEEN)?.toBooleanStrictOrNull() == true) {
            return false
        }
        if (alreadyPaired) {
            markOnboardingSeen()
            return false
        }
        return true
    }

    fun markOnboardingSeen() {
        keyValues.write(mapOf(KEY_ONBOARDING_SEEN to "true"))
    }

    companion object {
        const val PREFERENCES_NAME = "clipsync.firstrun"
        private const val KEY_ONBOARDING_SEEN = "firstrun.onboarding_seen"
    }
}
