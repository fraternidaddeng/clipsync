package com.clipsync.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Monochrome line icons transcribed 1:1 from docs/design/icons/ (the shared
 * "bone" — geometry must not be altered). 24-grid, stroke 1.7 (1.9 for
 * emphasised actions), round caps/joins, colour always from the container.
 */
object ClipSyncIcons {

    /** nav-history: two overlapping sheets. */
    val History: ImageVector by lazy {
        strokeIcon(
            "NavHistory",
            1.7f,
            "M10 3h9a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M16 3V1H3v14h3",
        )
    }

    /** nav-conduit: the polyline mark itself (brand = status). */
    val Conduit: ImageVector by lazy {
        strokeIcon(
            "NavConduit",
            1.7f,
            "M4 12h4l2-5 4 10 2-5h4",
        )
    }

    /** nav-prefs: three slider rails with knobs. */
    val Prefs: ImageVector by lazy {
        strokeIcon(
            "NavPrefs",
            1.7f,
            "M4 6h8.3M17.7 6H20",
            "M12.8 6a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0",
            "M4 12h2.3M11.7 12H20",
            "M6.8 12a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0",
            "M4 18h9.3M18.7 18H20",
            "M13.8 18a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0",
        )
    }

    /** act-search: magnifier, emphasised stroke 1.9. */
    val Search: ImageVector by lazy {
        strokeIcon(
            "ActSearch",
            1.9f,
            "M4 11a7 7 0 1 0 14 0a7 7 0 1 0-14 0",
            "M20 20l-4.2-4.2",
        )
    }

    /** Conduit segment: local service (foreground service heartbeat). */
    val Service: ImageVector by lazy {
        strokeIcon(
            "SegService",
            1.7f,
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
            "M12 2v3m0 14v3M4.2 4.2l2.1 2.1m11.4 11.4l2.1 2.1M2 12h3m14 0h3",
        )
    }

    /** Conduit segment: network (nearby waves + endpoint dot). */
    val Network: ImageVector by lazy {
        val builder = ImageVector.Builder(
            name = "SegNetwork",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        builder.addStroke("M5 12.5a7 7 0 0 1 14 0", 1.7f)
        builder.addStroke("M8.5 16a3.5 3.5 0 0 1 7 0", 1.7f)
        builder.addPath(
            pathData = addPathNodes("M10.8 19.5a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0-2.4 0"),
            fill = SolidColor(Color.Black),
        )
        builder.build()
    }

    /** dev-pc: monitor glyph = the Windows peer. */
    val Monitor: ImageVector by lazy {
        strokeIcon(
            "DevPc",
            1.8f,
            "M4 4h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M8 21h8m-4-4v4",
        )
    }
}

private fun strokeIcon(
    name: String,
    strokeWidth: Float,
    vararg paths: String,
): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    paths.forEach { builder.addStroke(it, strokeWidth) }
    return builder.build()
}

private fun ImageVector.Builder.addStroke(pathData: String, strokeWidth: Float) {
    addPath(
        pathData = addPathNodes(pathData),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}
