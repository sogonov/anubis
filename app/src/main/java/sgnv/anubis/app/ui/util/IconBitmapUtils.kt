package sgnv.anubis.app.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap

private const val MIN_ICON_DIMENSION = 1

fun Drawable.renderToBitmap(
    width: Int = intrinsicWidth.coerceAtLeast(MIN_ICON_DIMENSION),
    height: Int = intrinsicHeight.coerceAtLeast(MIN_ICON_DIMENSION)
): Bitmap {
    val bitmap = createBitmap(
        width.coerceAtLeast(MIN_ICON_DIMENSION),
        height.coerceAtLeast(MIN_ICON_DIMENSION)
    )
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

fun Drawable.renderToImageBitmap(): ImageBitmap = renderToBitmap().asImageBitmap()
