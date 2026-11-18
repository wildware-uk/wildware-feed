package dev.wildware.agents.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat

/**
 * Draws the launcher icon's two layers as the desktop's icon looks: a rounded tile with a little
 * room around it, so the app's mark (sidebar, drawer, sign-in) is the same robot everywhere.
 */
fun drawAppIcon(context: Context, size: Int = 256): ImageBitmap? = runCatching {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val margin = size * 0.08f
    val tile = size - 2 * margin
    val radius = tile * 0.225f
    canvas.clipPath(Path().apply { addRoundRect(RectF(margin, margin, size - margin, size - margin), radius, radius, Path.Direction.CW) })
    // An adaptive icon layer is 108 units across, of which the middle 72 show.
    val full = tile * 108f / 72f
    val offset = ((size - full) / 2).toInt()
    for (id in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
        val layer = ContextCompat.getDrawable(context, id) ?: continue
        layer.setBounds(offset, offset, offset + full.toInt(), offset + full.toInt())
        layer.draw(canvas)
    }
    bitmap.asImageBitmap()
}.getOrNull()
