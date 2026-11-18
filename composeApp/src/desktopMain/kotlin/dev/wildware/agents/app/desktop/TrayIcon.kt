package dev.wildware.agents.app.desktop

import dev.wildware.agents.app.ProductName

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** What the tray shows: the icon's badge and its hover text. */
data class TrayLook(val badge: String?, val tooltip: String) {
    companion object {
        /** [waiting] questions, or null while signed out. */
        fun of(waiting: Int?, offline: Boolean = false): TrayLook = when {
            waiting == null -> TrayLook(null, "$ProductName — signed out")
            waiting == 0 -> TrayLook(null, if (offline) "$ProductName — offline" else "$ProductName — nothing waiting")
            else -> TrayLook(
                if (waiting > 9) "9+" else waiting.toString(),
                "$ProductName — $waiting ${if (waiting == 1) "question" else "questions"} waiting" + if (offline) " (offline)" else "",
            )
        }
    }
}

/** How the tray icon is drawn. */
enum class TrayStyle {
    /** The app's own tile (packaging/icons/agents.png): Windows and Linux trays. */
    Tile,

    /** The robot on nothing, sized for the Mac menu bar, where a full tile looks heavy. */
    MenuBar,
}

/** The tray icon at [size] pixels, with an optional red [badge] in its top right corner. */
object TrayIconImage {
    private val Ink = Color(0x11, 0x12, 0x15)
    private val Blue = Color(0x2D, 0x74, 0xF0)
    private val Navy = Color(0x14, 0x21, 0x3D)
    private val Red = Color(0xE5, 0x48, 0x4D)

    /** The app icon (packaging/icons/agents.png, on the classpath) cropped to its tile, without the shadow around it. */
    private val tile: BufferedImage? by lazy {
        runCatching {
            val full = TrayIconImage::class.java.getResourceAsStream("/agents.png")?.use(ImageIO::read) ?: return@runCatching null
            val edge = full.width * 96 / 1024
            full.getSubimage(edge, edge, full.width - 2 * edge, full.height - 2 * edge)
        }.getOrNull()
    }

    fun draw(size: Int, badge: String? = null, style: TrayStyle = TrayStyle.Tile): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val base = tile
        when {
            // Blue rather than black or white: AWT cannot mark it as a template image, and blue reads on both menu bars.
            style == TrayStyle.MenuBar -> robot(g, 0.0, size.toDouble(), head = Blue, eyes = Color.WHITE)
            base != null && size >= DetailedFrom -> g.drawImage(base.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
            else -> {
                // The tile's margin would waste a tray's few pixels, so small sizes draw the robot larger on a full tile.
                g.color = Blue
                g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), size * 0.45, size * 0.45))
                robot(g, size * 0.14, size * 0.72, head = Color.WHITE, eyes = Navy)
            }
        }
        if (badge != null) badge(g, size, badge)
        g.dispose()
        return image
    }

    /** Below this the tray draws its own bold tile rather than shrinking the detailed icon. */
    private const val DetailedFrom = 48

    /**
     * The icon's robot in the square at [at] of side [side]: antenna, head and two eyes, in the
     * icon's own 1024 coordinates, cropped to the robot so it fills the square.
     */
    private fun robot(g: java.awt.Graphics2D, at: Double, side: Double, head: Color, eyes: Color) {
        // The robot spans x 252–772 and y 158–732 in the icon; this square holds it, centred.
        val span = 650.0
        val left = 512.0 - span / 2
        val top = 120.0
        fun x(v: Double) = at + (v - left) * side / span
        fun y(v: Double) = at + (v - top) * side / span
        fun l(v: Double) = v * side / span
        g.color = head
        g.fill(RoundRectangle2D.Double(x(500.0), y(200.0), l(24.0), l(100.0), l(24.0), l(24.0)))
        g.fill(Ellipse2D.Double(x(474.0), y(158.0), l(76.0), l(76.0)))
        g.fill(RoundRectangle2D.Double(x(252.0), y(292.0), l(520.0), l(440.0), l(340.0), l(340.0)))
        g.color = eyes
        g.fill(Ellipse2D.Double(x(342.0), y(446.0), l(104.0), l(132.0)))
        g.fill(Ellipse2D.Double(x(578.0), y(446.0), l(104.0), l(132.0)))
    }

    private fun badge(g: java.awt.Graphics2D, size: Int, badge: String) {
        val d = size * 0.62
        val x = size - d
        g.color = Red
        g.fillOval(x.toInt(), 0, d.toInt(), d.toInt())
        g.color = Ink
        g.stroke = BasicStroke((size / 32f).coerceAtLeast(1f))
        g.drawOval(x.toInt(), 0, d.toInt(), d.toInt())
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (d * if (badge.length > 1) 0.5 else 0.66).toInt().coerceAtLeast(6))
        val metrics = g.fontMetrics
        val tx = x + (d - metrics.stringWidth(badge)) / 2
        val ty = (d - metrics.height) / 2 + metrics.ascent
        g.drawString(badge, tx.toFloat(), ty.toFloat())
    }
}
