package com.clipsync.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun parsesReleaseTagsAndInformationalVersions() {
        assertEquals(AppVersion(0, 2, 0, 99), AppVersion.parseOrNull("0.2.0"))
        assertEquals(AppVersion(0, 2, 0, 99), AppVersion.parseOrNull("v0.2.0"))
        assertEquals(AppVersion(0, 1, 0, 2), AppVersion.parseOrNull("0.1.0-rc.2"))
        assertEquals(AppVersion(0, 3, 0, 99), AppVersion.parseOrNull("0.3.0+deadbeef"))
    }

    @Test
    fun rejectsShapesTheReleaseScriptsWouldAlsoReject() {
        assertNull(AppVersion.parseOrNull(""))
        assertNull(AppVersion.parseOrNull("1.0"))
        assertNull(AppVersion.parseOrNull("0.2.0-beta.1"))
        assertNull(AppVersion.parseOrNull("0.2.0-rc.0"))
        assertNull(AppVersion.parseOrNull("0.2.0-rc.99"))
    }

    @Test
    fun rankMatchesTheAndroidVersionCodeScheme() {
        assertEquals(10001, AppVersion.parseOrNull("0.1.0-rc.1")!!.rank)
        assertEquals(10002, AppVersion.parseOrNull("0.1.0-rc.2")!!.rank)
        assertEquals(10099, AppVersion.parseOrNull("0.1.0")!!.rank)
        assertEquals(20099, AppVersion.parseOrNull("0.2.0")!!.rank)
        assertEquals(30099, AppVersion.parseOrNull("0.3.0")!!.rank)
    }

    @Test
    fun rcSortsBelowItsFinalAndAboveThePreviousPatch() {
        assertTrue(AppVersion.compare("0.1.0-rc.2", "0.1.0") < 0)
        assertTrue(AppVersion.compare("0.1.0", "0.2.0-rc.1") < 0)
        assertEquals(0, AppVersion.compare("0.2.0", "0.2.0"))
        assertTrue(AppVersion.compare("v0.2.0", "0.3.0") < 0)
    }

    @Test
    fun unparseableLocalVersionLosesToARealRelease() {
        assertTrue(AppVersion.compare("dev", "0.2.0") < 0)
        assertTrue(AppVersion.compare("0.2.0", "???") > 0)
    }
}
