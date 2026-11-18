package dev.wildware.agents.app.design

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private val icon: ImageBitmap? by lazy {
    runCatching {
        val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("agents.png")!!.use { it.readBytes() }
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

actual fun appIconBitmap(): ImageBitmap? = icon
