package dev.wildware.agents.app.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** What the tray shows: the icon's badge and its hover text. */
data class TrayLook(val badge: String?, val tooltip: String) {
    companion object {
        /** [waiting] questions, or null while signed out. */
        fun of(waiting: Int?, offline: Boolean = false): TrayLook = when {
            waiting == null -> TrayLook(null, "Agents — signed out")
            waiting == 0 -> TrayLook(null, if (offline) "Agents — offline" else "Agents — nothing waiting")
            else -> TrayLook(
                if (waiting > 9) "9+" else waiting.toString(),
                "Agents — $waiting ${if (waiting == 1) "question" else "questions"} waiting" + if (offline) " (offline)" else "",
            )
        }
    }
}

/** How the tray icon is drawn. */
enum class TrayStyle {
    /** The app's own tile (packaging/icons/agents.png): Windows and Linux trays. */
    Tile,

    /** A lime radar on nothing, sized for the Mac menu bar, where a full tile looks heavy. */
    MenuBar,
}

/** The tray icon at [size] pixels, with an optional red [badge] in its top right corner. */
object TrayIconImage {
    private val Ink = Color(0x11, 0x12, 0x15)
    private val Lime = Color(0xC6, 0xF1, 0x69)
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
            style == TrayStyle.MenuBar -> radar(g, size, 0.0, size.toDouble())
            // The full icon's fine rings vanish at tray sizes, so small ones get the bold radar on the ink tile.
            base != null && size >= DetailedFrom -> g.drawImage(base.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
            else -> {
                g.color = Ink
                g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), size * 0.45, size * 0.45))
                radar(g, size, size * 0.12, size * 0.76)
            }
        }
        if (badge != null) badge(g, size, badge)
        g.dispose()
        return image
    }

    /** Below this the tray draws its own bold tile rather than shrinking the detailed icon. */
    private const val DetailedFrom = 48

    /** The icon's radar, drawn plainly in the square at [at] of side [side]: a ring, the sweep, the centre and the waiting blip. */
    private fun radar(g: java.awt.Graphics2D, size: Int, at: Double, side: Double) {
        val s = side / 32.0
        fun x(v: Double) = at + v * s
        g.color = Lime
        g.stroke = BasicStroke((2.0 * s).toFloat().coerceAtLeast(1f))
        g.draw(Ellipse2D.Double(x(3.0), x(3.0), 26 * s, 26 * s))
        g.fill(Arc2D.Double(x(3.0), x(3.0), 26 * s, 26 * s, 45.0, 45.0, Arc2D.PIE))
        g.fill(Ellipse2D.Double(x(13.5), x(13.5), 5 * s, 5 * s))
        g.fill(Ellipse2D.Double(x(19.5), x(19.5), 6 * s, 6 * s))
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
