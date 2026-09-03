package com.clipsync.android.docs

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyDocTest {
    private val base = "https://github.com/fraternidaddeng/clipsync/blob/main/docs/privacy-and-risks"

    @Test
    fun `chinese in any script or region reads the zh-CN edition`() {
        assertEquals("$base.zh-CN.md", privacyDocUrl("zh-Hans"))
        assertEquals("$base.zh-CN.md", privacyDocUrl("zh-Hant"))
        assertEquals("$base.zh-CN.md", privacyDocUrl("zh"))
        assertEquals("$base.zh-CN.md", privacyDocUrl("zh-Hant-TW"))
        assertEquals("$base.zh-CN.md", privacyDocUrl("zh_CN"))
    }

    @Test
    fun `japanese reads the ja edition`() {
        assertEquals("$base.ja.md", privacyDocUrl("ja"))
        assertEquals("$base.ja.md", privacyDocUrl("ja-JP"))
    }

    @Test
    fun `everything else reads the english default`() {
        assertEquals("$base.md", privacyDocUrl("en"))
        assertEquals("$base.md", privacyDocUrl("en-US"))
        assertEquals("$base.md", privacyDocUrl("ko"))
        assertEquals("$base.md", privacyDocUrl("pt-BR"))
        assertEquals("$base.md", privacyDocUrl(""))
    }

    @Test
    fun `the base is the repository the updater already trusts`() {
        assertEquals("https://github.com/fraternidaddeng/clipsync/blob/main/docs", DOCS_BASE_URL)
    }
}
