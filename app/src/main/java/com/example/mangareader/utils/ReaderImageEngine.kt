package com.example.mangareader.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.IOException
import kotlin.math.min

/**
 * Centralized image loading + scaling pipeline for reader screens.
 *
 * Why: both ReaderActivity and MangaPageAdapter need exactly the same fallback order
 * (file path -> content Uri -> assets) and the same display-safe scaling behavior.
 */
object ReaderImageEngine {

    private const val TAG = "ReaderImageEngine"
    private const val DEFAULT_MAX_WIDTH = 1080
    private const val DEFAULT_MAX_HEIGHT = 8192

    data class ScaledBitmapResult(
        val bitmap: Bitmap,
        val scaleYFromOriginal: Float
    )

    fun loadOriginalBitmap(context: Context, path: String): Bitmap? {
        if (path.isBlank()) return null

        BitmapFactory.decodeFile(path)?.let { return it }

        try {
            context.contentResolver.openInputStream(Uri.parse(path))?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed content Uri decode: $path", e)
        }

        return try {
            context.assets.open(path).use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (_: IOException) {
            null
        }
    }

    fun loadScaledBitmap(
        context: Context,
        path: String,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        maxHeight: Int = DEFAULT_MAX_HEIGHT
    ): ScaledBitmapResult? {
        val original = loadOriginalBitmap(context, path) ?: return null
        return scaleBitmapForDisplay(original, maxWidth, maxHeight)
    }

    fun scaleBitmapForDisplay(
        bitmap: Bitmap,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        maxHeight: Int = DEFAULT_MAX_HEIGHT
    ): ScaledBitmapResult {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return ScaledBitmapResult(bitmap, 1.0f)
        }

        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = min(scaleWidth, scaleHeight)

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Scaling bitmap: ${width}x${height} -> ${newWidth}x${newHeight} (scale=$scale)")

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        if (scaledBitmap != bitmap) {
            bitmap.recycle()
        }

        return ScaledBitmapResult(
            bitmap = scaledBitmap,
            scaleYFromOriginal = scaledBitmap.height.toFloat() / height
        )
    }
}
