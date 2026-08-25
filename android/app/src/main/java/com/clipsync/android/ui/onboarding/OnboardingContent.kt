package com.clipsync.android.ui.onboarding

/** One of the three places explained on the first-run screen. */
data class OnboardingTabEntry(
    val title: String,
    val description: String,
)

/**
 * The first-run copy as pure data, separate from the composable: what the app
 * promises here is a commitment (honesty about capabilities, pairing lives
 * under 通路), so tests can hold the words to it.
 */
object OnboardingContent {
    /** Serif greeting — the app's own voice, reusing the brand moment. */
    const val TITLE = "剪剪相传"
    const val SUBTITLE = "剪贴板在手机与电脑之间安静地流动。"

    /** In dock order; the icons are matched positionally by the composable. */
    val tabs = listOf(
        OnboardingTabEntry(
            title = "历史",
            description = "两端复制过的内容都汇在这里，轻触任意一条即可再次复制到本机。",
        ),
        OnboardingTabEntry(
            title = "通路",
            description = "内容流动的每一段都如实显示。与电脑配对的入口就在这里——网络段的「去配对」。",
        ),
        OnboardingTabEntry(
            title = "偏好",
            description = "暂停同步、私密模式、保留期——每个开关都真实生效，改动立即落盘。",
        ),
    )

    const val HONESTY_HEADER = "先说清楚"
    const val HONESTY_BODY =
        "Android 限制应用在后台读取剪贴板，剪剪相传不会假装做到：" +
            "应用在前台时可自动上行；后台读取需要在「通路」页任选一条路线完成授权，" +
            "每一段能力都以实测为准，做不到就直说。"

    const val ACTION_PAIR = "去配对"
    const val ACTION_SKIP = "先看看，稍后配对"
}
