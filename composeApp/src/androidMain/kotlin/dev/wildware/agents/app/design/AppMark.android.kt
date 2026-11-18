package dev.wildware.agents.app.design

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The launcher icon as a picture. The icon is an adaptive icon in the app's resources, which only
 * the app can reach, so the app draws it here once as it starts.
 */
object AndroidAppIcon {
    @Volatile
    var bitmap: ImageBitmap? = null
}

actual fun appIconBitmap(): ImageBitmap? = AndroidAppIcon.bitmap
