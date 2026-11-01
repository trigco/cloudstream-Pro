package com.lagradost.cloudstream3.utils

import android.graphics.Bitmap
import android.graphics.Color
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

/**
 * Phase 8.102 round 2: blur-fill for the details header backdrop.
 *
 * The header shows the poster art twice: sharp with fitCenter on
 * top (whole art, uncropped) and — behind it — this transformed
 * copy cropped to fill the whole header, so a portrait poster no
 * longer leaves bare black on either side.
 *
 * Deliberately cheap and old-API safe (the user's phone is API
 * 27): the source is downscaled to a sliver first, then blurred
 * with a plain sliding-window box blur and darkened in the same
 * pixel pass, so the top-bar icons stay readable over it. No
 * RenderEffect (API 31+) and no RenderScript (deprecated).
 */
class BlurFillTransformation : Transformation() {

    override val cacheKey: String = "cs3-blurfill-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val source = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }
        if (source.width <= 0 || source.height <= 0) return source

        val targetWidth = minOf(TARGET_WIDTH, source.width)
        val targetHeight = maxOf(
            1,
            (source.height * (targetWidth.toFloat() / source.width)).roundToInt()
        )
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        val small = if (scaled.isMutable && scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, true)
        }

        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val buffer = IntArray(pixels.size)
        repeat(BLUR_PASSES) {
            blurAxis(pixels, buffer, small.width, small.height, BLUR_RADIUS, horizontal = true)
            blurAxis(buffer, pixels, small.width, small.height, BLUR_RADIUS, horizontal = false)
        }
        darken(pixels, DARKEN_FACTOR)
        small.setPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        return small
    }

    /** One sliding-window box-blur pass along a single axis. */
    private fun blurAxis(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
        val window = 2 * radius + 1
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height

        fun at(line: Int, index: Int): Int =
            if (horizontal) src[line * width + index] else src[index * width + line]

        fun put(line: Int, index: Int, value: Int) {
            if (horizontal) dst[line * width + index] = value else dst[index * width + line] = value
        }

        for (line in 0 until lines) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            // Prime the window centred on index 0; edges clamp.
            for (k in -radius..radius) {
                val p = at(line, k.coerceIn(0, length - 1))
                a += Color.alpha(p)
                r += Color.red(p)
                g += Color.green(p)
                b += Color.blue(p)
            }
            for (index in 0 until length) {
                put(line, index, Color.argb(a / window, r / window, g / window, b / window))
                val leaving = at(line, (index - radius).coerceIn(0, length - 1))
                val entering = at(line, (index + radius + 1).coerceIn(0, length - 1))
                a += Color.alpha(entering) - Color.alpha(leaving)
                r += Color.red(entering) - Color.red(leaving)
                g += Color.green(entering) - Color.green(leaving)
                b += Color.blue(entering) - Color.blue(leaving)
            }
        }
    }

    /** Scales RGB toward black; alpha is kept. */
    private fun darken(pixels: IntArray, factor: Float) {
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = Color.argb(
                Color.alpha(p),
                (Color.red(p) * factor).toInt(),
                (Color.green(p) * factor).toInt(),
                (Color.blue(p) * factor).toInt()
            )
        }
    }

    private companion object {
        const val TARGET_WIDTH = 48
        const val BLUR_RADIUS = 6
        const val BLUR_PASSES = 2
        const val DARKEN_FACTOR = 0.55f
    }
}
