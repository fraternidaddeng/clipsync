package com.clipsync.android.platform.clipboard.adblog.fixtures

import com.clipsync.android.platform.clipboard.adblog.ClipboardLogRomFamily

/**
 * Anonymized logcat templates for parser tests. These are not real device
 * captures: timestamps, pids, and tokens are synthetic, and no clipboard
 * body or real package name is stored.
 */
object ClipboardLogFixtures {
    data class Fixture(
        val family: ClipboardLogRomFamily,
        val parserVersion: String,
        val line: String,
    )

    val AOSP_MATCHED: List<Fixture> = listOf(
        fixture(ClipboardLogRomFamily.AOSP, "aosp-v1", "I ClipboardService: Copied to clipboard."),
        fixture(ClipboardLogRomFamily.AOSP, "aosp-v1", "I ClipboardService: Copied to clipboard"),
        fixture(ClipboardLogRomFamily.AOSP, "aosp-v1", "I ClipboardService: setPrimaryClip"),
        fixture(ClipboardLogRomFamily.AOSP, "aosp-v1", "I ClipboardService: setPrimaryClip()"),
        fixture(ClipboardLogRomFamily.AOSP, "aosp-v1", "I ClipboardService: notifyPrimaryClipChanged"),
        fixture(
            ClipboardLogRomFamily.AOSP,
            "aosp-v1",
            "01-01 00:00:00.000  1000  1000 I ClipboardService: Copied to clipboard.",
        ),
        fixture(
            ClipboardLogRomFamily.AOSP,
            "aosp-v1",
            "I/ClipboardService( 1000): setPrimaryClip",
        ),
    )

    val ONE_UI_MATCHED: List<Fixture> = listOf(
        fixture(ClipboardLogRomFamily.ONE_UI, "oneui-v1", "I SemClipboardService: setPrimaryClip"),
        fixture(ClipboardLogRomFamily.ONE_UI, "oneui-v1", "I SemClipboardService: notifyChanged"),
        fixture(ClipboardLogRomFamily.ONE_UI, "oneui-v1", "I SemClipboardService: updateClip"),
        fixture(ClipboardLogRomFamily.ONE_UI, "oneui-v1", "I SemClipboardService: setPrimaryClipInternal"),
        fixture(
            ClipboardLogRomFamily.ONE_UI,
            "oneui-v1",
            "01-01 00:00:00.000  1000  1000 I SemClipboardService: notifyChanged",
        ),
    )

    val MIUI_HYPEROS_MATCHED: List<Fixture> = listOf(
        fixture(ClipboardLogRomFamily.MIUI_HYPEROS, "miui-hyperos-v1", "I MiuiClipboardService: notifyClipChanged"),
        fixture(ClipboardLogRomFamily.MIUI_HYPEROS, "miui-hyperos-v1", "I HyperClipboardService: setPrimaryClip"),
        fixture(ClipboardLogRomFamily.MIUI_HYPEROS, "miui-hyperos-v1", "I MiuiClipboardManager: onPrimaryClipChanged"),
        fixture(
            ClipboardLogRomFamily.MIUI_HYPEROS,
            "miui-hyperos-v1",
            "01-01 00:00:00.000  1000  1000 I HyperClipboardService: notifyClipChanged",
        ),
    )

    val COLOROS_ORIGINOS_MATCHED: List<Fixture> = listOf(
        fixture(ClipboardLogRomFamily.COLOROS_ORIGINOS, "coloros-originos-v1", "I OplusClipboardService: onPrimaryClipChanged"),
        fixture(ClipboardLogRomFamily.COLOROS_ORIGINOS, "coloros-originos-v1", "I ColorClipboardService: notifyPrimaryClipChanged"),
        fixture(ClipboardLogRomFamily.COLOROS_ORIGINOS, "coloros-originos-v1", "I ClipboardServiceExtImpl: setPrimaryClip"),
        fixture(
            ClipboardLogRomFamily.COLOROS_ORIGINOS,
            "coloros-originos-v1",
            "01-01 00:00:00.000  1000  1000 I OplusClipboardService: setPrimaryClip",
        ),
    )

    val AOSP_UNMATCHED: List<String> = listOf(
        "E ClipboardService: Denying clipboard access to pkg.app, application is not in focus nor is it a system service for user 0",
        "W ClipboardService: Clipboard access denied to 10123/pkg.app",
        "I ClipboardService: addPrimaryClipChangedListener invalid deviceId for userId:0",
        "I ClipboardService: Could not grant permission to primary clip. Clearing clipboard.",
        "I ClipboardService: setPrimaryClip: <redacted>",
        "I SemClipboardService: setPrimaryClip",
    )

    val ONE_UI_UNMATCHED: List<String> = listOf(
        "I ClipboardService: Copied to clipboard.",
        "D SemClipboardService: dumpInternal",
        "I SemClipboardService: getPrimaryClip",
        "I MiuiClipboardService: notifyClipChanged",
    )

    val MIUI_HYPEROS_UNMATCHED: List<String> = listOf(
        "I ClipboardService: setPrimaryClip",
        "D MiuiClipboardService: dumpState",
        "I MiuiClipboardManager: getPrimaryClip skipped",
        "I OplusClipboardService: onPrimaryClipChanged",
    )

    val COLOROS_ORIGINOS_UNMATCHED: List<String> = listOf(
        "I ClipboardService: notifyPrimaryClipChanged",
        "I ClipboardServiceExt: dumpState",
        "D OplusClipboard: getPrimaryClip skipped",
        "I SemClipboardService: notifyChanged",
    )

    val UNKNOWN_UNMATCHED: List<String> = listOf(
        "I SystemServer: boot completed",
        "W WindowManager: Failed to find window",
        "I ActivityTaskManager: START u0",
        "hello world",
        "",
        "I ClipboardWatcher: user copied something",
    )

    val ALL_MATCHED: List<Fixture> =
        AOSP_MATCHED + ONE_UI_MATCHED + MIUI_HYPEROS_MATCHED + COLOROS_ORIGINOS_MATCHED

    val ALL_UNMATCHED: List<String> =
        (AOSP_UNMATCHED + ONE_UI_UNMATCHED + MIUI_HYPEROS_UNMATCHED +
            COLOROS_ORIGINOS_UNMATCHED + UNKNOWN_UNMATCHED)
            .distinct()
            .filter { line -> ALL_MATCHED.none { it.line == line } }

    private fun fixture(
        family: ClipboardLogRomFamily,
        parserVersion: String,
        line: String,
    ): Fixture = Fixture(family, parserVersion, line)
}
