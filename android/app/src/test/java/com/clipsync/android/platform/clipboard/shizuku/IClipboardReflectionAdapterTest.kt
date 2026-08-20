package com.clipsync.android.platform.clipboard.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IClipboardReflectionAdapterTest {
    @Test
    fun `sdk 29 selects pkg plus userId shape`() {
        assertEquals(IClipboardApiShape.PKG_USERID, IClipboardApiShape.forSdk(29))
    }

    @Test
    fun `sdk 30 through 33 select pkg attribution userId shape`() {
        for (sdk in 30..33) {
            assertEquals(
                "sdk $sdk",
                IClipboardApiShape.PKG_ATTRIBUTION_USERID,
                IClipboardApiShape.forSdk(sdk),
            )
        }
    }

    @Test
    fun `sdk 34 and 35 select attribution plus deviceId shape`() {
        assertEquals(IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE, IClipboardApiShape.forSdk(34))
        assertEquals(IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE, IClipboardApiShape.forSdk(35))
    }

    @Test
    fun `api 29 shape reads and writes via pkg userId`() {
        val clipboard = FakeClipboardPkgUserId(clip = FakeClip("api29"))
        val adapter = IClipboardReflectionAdapter(
            clipboard = clipboard,
            sdkInt = 29,
            callingPackage = "com.android.shell",
            userId = 0,
        )

        assertEquals(IClipboardApiShape.PKG_USERID, adapter.selectedShape)
        assertEquals(ClipboardAdapterResult.Text("api29"), adapter.getPrimaryClipText())
        assertEquals("com.android.shell", clipboard.lastGetPkg)
        assertEquals(0, clipboard.lastGetUser)

        val written = Any()
        assertEquals(ClipboardAdapterResult.Empty, adapter.setPrimaryClip(written))
        assertEquals(written, clipboard.lastSetClip)
        assertEquals("com.android.shell", clipboard.lastSetPkg)
        assertEquals(0, clipboard.lastSetUser)

        val listener = Any()
        assertEquals(ClipboardAdapterResult.Empty, adapter.addPrimaryClipChangedListener(listener))
        assertEquals(listener, clipboard.addedListener)
        assertEquals(ClipboardAdapterResult.Empty, adapter.removePrimaryClipChangedListener(listener))
        assertEquals(listener, clipboard.removedListener)
    }

    @Test
    fun `api 30 through 33 invoke attribution tag methods and one-arg remove`() {
        for (sdk in 30..33) {
            val clipboard = FakeClipboardApi30(clip = FakeClip("sdk$sdk"))
            val adapter = IClipboardReflectionAdapter(
                clipboard = clipboard,
                sdkInt = sdk,
                callingPackage = "com.android.shell",
                userId = 10,
                attributionTag = "tag",
            )
            assertEquals(IClipboardApiShape.PKG_ATTRIBUTION_USERID, adapter.selectedShape)
            assertEquals(ClipboardAdapterResult.Text("sdk$sdk"), adapter.getPrimaryClipText())
            assertEquals("com.android.shell", clipboard.lastGetPkg)
            assertEquals("tag", clipboard.lastGetAttribution)
            assertEquals(10, clipboard.lastGetUser)

            val clip = Any()
            assertEquals(ClipboardAdapterResult.Empty, adapter.setPrimaryClip(clip))
            assertEquals(clip, clipboard.lastSetClip)
            assertEquals("com.android.shell", clipboard.lastSetPkg)
            assertEquals("tag", clipboard.lastSetAttribution)
            assertEquals(10, clipboard.lastSetUser)

            val listener = Any()
            assertEquals(ClipboardAdapterResult.Empty, adapter.addPrimaryClipChangedListener(listener))
            assertEquals(listener, clipboard.addedListener)
            assertEquals("com.android.shell", clipboard.lastAddPkg)
            assertEquals("tag", clipboard.lastAddAttribution)
            assertEquals(10, clipboard.lastAddUser)
            assertEquals(ClipboardAdapterResult.Empty, adapter.removePrimaryClipChangedListener(listener))
            assertEquals(listener, clipboard.removedListener)
        }
    }

    @Test
    fun `adapter on sdk 33 uses FakeClipboardApi33 three-arg get and one-arg remove`() {
        val clipboard = FakeClipboardApi33(clip = FakeClip("api33"))
        val adapter = IClipboardReflectionAdapter(
            clipboard = clipboard,
            sdkInt = 33,
            callingPackage = "com.android.shell",
            userId = 0,
            attributionTag = null,
        )
        assertEquals(IClipboardApiShape.PKG_ATTRIBUTION_USERID, adapter.selectedShape)
        assertEquals(ClipboardAdapterResult.Text("api33"), adapter.getPrimaryClipText())
        assertEquals(null, clipboard.lastGetAttribution)

        val listener = Any()
        assertEquals(ClipboardAdapterResult.Empty, adapter.addPrimaryClipChangedListener(listener))
        assertEquals(ClipboardAdapterResult.Empty, adapter.removePrimaryClipChangedListener(listener))
        assertEquals(listener, clipboard.removedListener)
    }

    @Test
    fun `api 34 and 35 invoke attribution tag and device id`() {
        for (sdk in 34..35) {
            val clipboard = FakeClipboardApi34(clip = FakeClip("api$sdk"))
            val adapter = IClipboardReflectionAdapter(
                clipboard = clipboard,
                sdkInt = sdk,
                callingPackage = "com.android.shell",
                userId = 0,
                attributionTag = null,
                deviceId = 0,
            )
            assertEquals(IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE, adapter.selectedShape)
            assertEquals(ClipboardAdapterResult.Text("api$sdk"), adapter.getPrimaryClipText())
            assertEquals("com.android.shell", clipboard.lastGetPkg)
            assertEquals(null, clipboard.lastGetAttribution)
            assertEquals(0, clipboard.lastGetUser)
            assertEquals(0, clipboard.lastGetDevice)

            val clip = Any()
            adapter.setPrimaryClip(clip)
            assertEquals(clip, clipboard.lastSetClip)
            assertEquals(0, clipboard.lastSetDevice)

            val listener = Any()
            adapter.addPrimaryClipChangedListener(listener)
            assertEquals(listener, clipboard.addedListener)
            adapter.removePrimaryClipChangedListener(listener)
            assertEquals(listener, clipboard.removedListener)
        }
    }

    @Test
    fun `intermediate attribution shape is used as fallback when preferred is missing`() {
        val clipboard = FakeClipboardPkgAttributionUserId(clip = FakeClip("oem"))
        val adapter = IClipboardReflectionAdapter(
            clipboard = clipboard,
            sdkInt = 35,
            callingPackage = "com.android.shell",
            attributionTag = "tag",
            userId = 0,
        )
        assertEquals(IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE, adapter.selectedShape)
        assertEquals(ClipboardAdapterResult.Text("oem"), adapter.getPrimaryClipText())
        assertEquals("tag", clipboard.lastGetAttribution)
    }

    @Test
    fun `boolean false add listener is a clipboard failure`() {
        val clipboard = FakeClipboardBooleanListener(ok = false)
        val adapter = IClipboardReflectionAdapter(
            clipboard = clipboard,
            sdkInt = 29,
            callingPackage = "com.android.shell",
        )
        assertEquals(
            ClipboardAdapterResult.Failed(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD),
            adapter.addPrimaryClipChangedListener(Any()),
        )
        clipboard.ok = true
        assertEquals(
            ClipboardAdapterResult.Empty,
            adapter.addPrimaryClipChangedListener(Any()),
        )
    }

    @Test
    fun `missing methods map to api mismatch`() {
        val adapter = IClipboardReflectionAdapter(
            clipboard = Any(),
            sdkInt = 29,
            callingPackage = "com.android.shell",
        )
        assertEquals(
            ClipboardAdapterResult.Failed(ShizukuErrorCodes.API_MISMATCH),
            adapter.getPrimaryClipText(),
        )
        assertEquals(
            ClipboardAdapterResult.Failed(ShizukuErrorCodes.API_MISMATCH),
            adapter.setPrimaryClip(Any()),
        )
        assertEquals(
            ClipboardAdapterResult.Failed(ShizukuErrorCodes.API_MISMATCH),
            adapter.addPrimaryClipChangedListener(Any()),
        )
        assertEquals(
            ClipboardAdapterResult.Failed(ShizukuErrorCodes.API_MISMATCH),
            adapter.removePrimaryClipChangedListener(Any()),
        )
    }

    @Test
    fun `dead object exception maps to clipboard binder dead`() {
        val clipboard = FakeClipboardPkgUserId(onGet = { throw DeadObjectException() })
        val adapter = IClipboardReflectionAdapter(
            clipboard = clipboard,
            sdkInt = 29,
            callingPackage = "com.android.shell",
        )
        assertEquals(
            ClipboardAdapterResult.Failed(ShizukuErrorCodes.CLIPBOARD_BINDER_DEAD),
            adapter.getPrimaryClipText(),
        )
    }

    @Test
    fun `empty clip is empty not a mismatch`() {
        val clipboard = FakeClipboardPkgUserId(clip = FakeClip(null))
        val adapter = IClipboardReflectionAdapter(
            clipboard = clipboard,
            sdkInt = 29,
            callingPackage = "com.android.shell",
        )
        assertEquals(ClipboardAdapterResult.Empty, adapter.getPrimaryClipText())
    }

    @Test
    fun `shape param counts match documented IClipboard families`() {
        assertEquals(2, IClipboardApiShape.PKG_USERID.paramCount(MethodKind.GET))
        assertEquals(3, IClipboardApiShape.PKG_USERID.paramCount(MethodKind.SET))
        assertEquals(4, IClipboardApiShape.PKG_ATTRIBUTION_USERID.paramCount(MethodKind.LISTENER))
        assertEquals(5, IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE.paramCount(MethodKind.SET))
        assertEquals(5, IClipboardApiShape.PKG_ATTRIBUTION_USERID_DEVICE.paramCount(MethodKind.REMOVE))
        assertTrue(IClipboardApiShape.entries.size >= 3)
    }

    class DeadObjectException : RuntimeException()

    class FakeClip(private val text: String?) {
        fun getItemCount(): Int = if (text == null) 0 else 1

        fun getItemAt(index: Int): FakeItem = FakeItem(text)
    }

    class FakeItem(private val text: String?) {
        fun getText(): CharSequence? = text
    }

    class FakeClipboardPkgUserId(
        var clip: Any? = null,
        private val onGet: () -> Any? = { clip },
    ) {
        var lastGetPkg: String? = null
        var lastGetUser: Int? = null
        var lastSetClip: Any? = null
        var lastSetPkg: String? = null
        var lastSetUser: Int? = null
        var addedListener: Any? = null
        var removedListener: Any? = null

        fun getPrimaryClip(pkg: String, userId: Int): Any? {
            lastGetPkg = pkg
            lastGetUser = userId
            return onGet()
        }

        fun setPrimaryClip(clip: Any, callingPackage: String, userId: Int) {
            lastSetClip = clip
            lastSetPkg = callingPackage
            lastSetUser = userId
        }

        fun addPrimaryClipChangedListener(listener: Any, callingPackage: String, userId: Int) {
            addedListener = listener
            lastSetPkg = callingPackage
            lastSetUser = userId
        }

        fun removePrimaryClipChangedListener(listener: Any, callingPackage: String, userId: Int) {
            removedListener = listener
        }
    }

    /** AOSP API 30–33: 3-arg get/set/add and listener-only remove. */
    open class FakeClipboardApi30(var clip: Any? = null) {
        var lastGetPkg: String? = null
        var lastGetAttribution: String? = null
        var lastGetUser: Int? = null
        var lastSetClip: Any? = null
        var lastSetPkg: String? = null
        var lastSetAttribution: String? = null
        var lastSetUser: Int? = null
        var addedListener: Any? = null
        var lastAddPkg: String? = null
        var lastAddAttribution: String? = null
        var lastAddUser: Int? = null
        var removedListener: Any? = null

        fun getPrimaryClip(pkg: String, attributionTag: String?, userId: Int): Any? {
            lastGetPkg = pkg
            lastGetAttribution = attributionTag
            lastGetUser = userId
            return clip
        }

        fun setPrimaryClip(
            clip: Any,
            callingPackage: String,
            attributionTag: String?,
            userId: Int,
        ) {
            lastSetClip = clip
            lastSetPkg = callingPackage
            lastSetAttribution = attributionTag
            lastSetUser = userId
        }

        fun addPrimaryClipChangedListener(
            listener: Any,
            callingPackage: String,
            attributionTag: String?,
            userId: Int,
        ) {
            addedListener = listener
            lastAddPkg = callingPackage
            lastAddAttribution = attributionTag
            lastAddUser = userId
        }

        fun removePrimaryClipChangedListener(listener: Any) {
            removedListener = listener
        }
    }

    class FakeClipboardApi33(clip: Any? = null) : FakeClipboardApi30(clip)

    class FakeClipboardPkgAttributionUserId(var clip: Any? = null) {
        var lastGetAttribution: String? = null

        fun getPrimaryClip(pkg: String, attributionTag: String?, userId: Int): Any? {
            lastGetAttribution = attributionTag
            return clip
        }
    }

    class FakeClipboardApi34(var clip: Any? = null) {
        var lastGetPkg: String? = null
        var lastGetAttribution: String? = null
        var lastGetUser: Int? = null
        var lastGetDevice: Int? = null
        var lastSetClip: Any? = null
        var lastSetDevice: Int? = null
        var addedListener: Any? = null
        var removedListener: Any? = null

        fun getPrimaryClip(
            pkg: String,
            attributionTag: String?,
            userId: Int,
            deviceId: Int,
        ): Any? {
            lastGetPkg = pkg
            lastGetAttribution = attributionTag
            lastGetUser = userId
            lastGetDevice = deviceId
            return clip
        }

        fun setPrimaryClip(
            clip: Any,
            callingPackage: String,
            attributionTag: String?,
            userId: Int,
            deviceId: Int,
        ) {
            lastSetClip = clip
            lastSetDevice = deviceId
        }

        fun addPrimaryClipChangedListener(
            listener: Any,
            callingPackage: String,
            attributionTag: String?,
            userId: Int,
            deviceId: Int,
        ) {
            addedListener = listener
        }

        fun removePrimaryClipChangedListener(
            listener: Any,
            callingPackage: String,
            attributionTag: String?,
            userId: Int,
            deviceId: Int,
        ) {
            removedListener = listener
        }
    }

    class FakeClipboardBooleanListener(var ok: Boolean) {
        fun addPrimaryClipChangedListener(listener: Any, callingPackage: String, userId: Int): Boolean {
            return ok
        }
    }
}
