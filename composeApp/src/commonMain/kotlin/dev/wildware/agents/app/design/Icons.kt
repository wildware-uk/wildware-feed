package dev.wildware.agents.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** A small set of line icons drawn on a 24-unit grid, so the app needs no icon library. */
object Icons {
    val Search = icon("search", circle(11f, 11f, 7f), "M21 21 16.5 16.5")
    val Send = icon("send", "M12 19V5", "M5 12 12 5 19 12")
    val Mic = icon("mic", "M12 2A3 3 0 0 0 9 5V12A3 3 0 0 0 15 12V5A3 3 0 0 0 12 2Z", "M19 10V12A7 7 0 0 1 5 12V10", "M12 19V22")
    val Plus = icon("plus", "M12 5V19", "M5 12H19")
    val ChevronDown = icon("chevron-down", "M6 9 12 15 18 9")
    val ChevronRight = icon("chevron-right", "M9 6 15 12 9 18")
    val Paperclip = icon(
        "paperclip",
        "M21.44 11.05 12.25 20.24A6 6 0 0 1 3.76 11.75L12.95 2.56A4 4 0 0 1 18.61 8.22L9.41 17.41A2 2 0 0 1 6.59 14.59L15.07 6.1",
    )
    val Refresh = icon("refresh", "M21 12A9 9 0 1 1 18.36 5.64", "M21 3V9H15")
    val Back = icon("back", "M19 12H5", "M12 19 5 12 12 5")
    val Hash = icon("hash", "M4 9H20", "M4 15H20", "M10 3 8 21", "M16 3 14 21")
    val Bell = icon(
        "bell",
        "M6 8A6 6 0 0 1 18 8C18 15 21 17 21 17H3S6 15 6 8",
        "M10.3 21A1.94 1.94 0 0 0 13.7 21",
    )
    val Settings = icon(
        "settings",
        "M4 21V14", "M4 10V3", "M12 21V12", "M12 8V3", "M20 21V16", "M20 12V3",
        "M1 14H7", "M9 8H15", "M17 16H23",
    )
    val Check = icon("check", "M20 6 9 17 4 12")
    val Reply = icon("reply", "M9 14 4 9 9 4", "M20 20V13A4 4 0 0 0 16 9H4")
    /** A text cursor (I-beam): select text. */
    val SelectText = icon("select-text", "M9 4h6", "M9 20h6", "M12 4v16")
    val More = icon("more", circle(5f, 12f, 1f), circle(12f, 12f, 1f), circle(19f, 12f, 1f))
    val Copy = icon(
        "copy",
        rect(9f, 9f, 13f, 13f, 2f),
        "M5 15H4A2 2 0 0 1 2 13V4A2 2 0 0 1 4 2H13A2 2 0 0 1 15 4V5",
    )
    val Smile = icon(
        "smile",
        circle(12f, 12f, 10f),
        "M8 14S9.5 16 12 16 16 14 16 14",
        "M9 9H9.01", "M15 9H15.01",
    )
    val Close = icon("close", "M18 6 6 18", "M6 6 18 18")
    val Inbox = icon(
        "inbox",
        "M22 12H16L14 15H10L8 12H2",
        "M5.45 5.11 2 12V18A2 2 0 0 0 4 20H20A2 2 0 0 0 22 18V12L18.55 5.11A2 2 0 0 0 16.76 4H7.24A2 2 0 0 0 5.45 5.11Z",
    )
    val At = icon("at", circle(12f, 12f, 4f), "M16 8V13A3 3 0 0 0 22 13V12A10 10 0 1 0 18.08 19.94")
    val Image = icon(
        "image",
        rect(3f, 3f, 18f, 18f, 2f),
        circle(9f, 9f, 2f),
        "M21 15 17.91 11.91A2 2 0 0 0 15.09 11.91L6 21",
    )
    val Clock = icon("clock", circle(12f, 12f, 10f), "M12 6V12L16 14")
    val SignOut = icon("sign-out", "M9 21H5A2 2 0 0 1 3 19V5A2 2 0 0 1 5 3H9", "M16 17 21 12 16 7", "M21 12H9")
    val Moon = icon("moon", "M12 3A6 6 0 0 0 21 12 9 9 0 1 1 12 3Z")
    val Grip = icon("grip", "M5 9H19", "M5 15H19")
    val ZoomIn = icon("zoom-in", circle(11f, 11f, 7f), "M21 21 16.5 16.5", "M11 8V14", "M8 11H14")
    val ZoomOut = icon("zoom-out", circle(11f, 11f, 7f), "M21 21 16.5 16.5", "M8 11H14")
    val Link = icon("link", "M10 13A5 5 0 0 0 17.54 13.54L20.54 10.54A5 5 0 0 0 13.47 3.47L11.75 5.18", "M14 11A5 5 0 0 0 6.46 10.46L3.46 13.46A5 5 0 0 0 10.53 20.53L12.24 18.82")
    val Trash = icon("trash", "M3 6H21", "M19 6V20A2 2 0 0 1 17 22H7A2 2 0 0 1 5 20V6", "M8 6V4A2 2 0 0 1 10 2H14A2 2 0 0 1 16 4V6")
    val Pencil = icon("pencil", "M12 20H21", "M16.5 3.5A2.12 2.12 0 0 1 19.5 6.5L7 19L3 20L4 16Z")
    val Details = icon("details", "M8 6H21", "M8 12H21", "M8 18H21", "M3 6H3.01", "M3 12H3.01", "M3 18H3.01")
    val Download = icon("download", "M21 15V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V15", "M7 10 12 15 17 10", "M12 15V3")
    val Pin = icon(
        "pin",
        "M12 17V22",
        "M9 10.76A2 2 0 0 1 7.89 12.55L6.11 13.45A2 2 0 0 0 5 15.24V16A1 1 0 0 0 6 17H18A1 1 0 0 0 19 16V15.24A2 2 0 0 0 17.89 13.45L16.11 12.55A2 2 0 0 1 15 10.76V7A1 1 0 0 1 16 6A2 2 0 0 0 16 2H8A2 2 0 0 0 8 6A1 1 0 0 1 9 7Z",
    )
    val ArrowUp = icon("arrow-up", "M12 19V5", "M5 12 12 5 19 12")
    val Play = icon("play", "M7 4 19 12 7 20Z")
    val Pause = icon("pause", "M8 5V19", "M16 5V19")
    val Volume = icon("volume", "M11 5 6 9H2V15H6L11 19Z", "M15.5 8.5A5 5 0 0 1 15.5 15.5", "M19 5A10 10 0 0 1 19 19")
    val Muted = icon("muted", "M11 5 6 9H2V15H6L11 19Z", "M22 9 16 15", "M16 9 22 15")
    val Expand = icon("expand", "M15 3H21V9", "M9 21H3V15", "M21 3 14 10", "M3 21 10 14")
    val File = icon("file", "M14 2H6A2 2 0 0 0 4 4V20A2 2 0 0 0 6 22H18A2 2 0 0 0 20 20V8Z", "M14 2V8H20")
    val Sliders = icon(
        "sliders",
        "M4 6H13", "M19 6H20", circle(16f, 6f, 2.5f),
        "M4 12H7", "M13 12H20", circle(10f, 12f, 2.5f),
        "M4 18H13", "M19 18H20", circle(16f, 18f, 2.5f),
    )
    val Warning = icon("warning", "M10.3 3.9 1.8 18A2 2 0 0 0 3.5 21H20.5A2 2 0 0 0 22.2 18L13.7 3.9A2 2 0 0 0 10.3 3.9Z", "M12 9V13", "M12 17H12.01")
}

private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}A$r $r 0 1 0 ${cx + r} ${cy}A$r $r 0 1 0 ${cx - r} ${cy}Z"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}" +
        "A$r $r 0 0 1 ${x + w - r} ${y + h}H${x + r}A$r $r 0 0 1 $x ${y + h - r}V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"
