package com.althmany.groupmanager.domain

import kotlin.math.max

/**
 * Screenshot-only compatibility detector for Samsung Work/Secure profiles whose shell-owned
 * UiAutomation tree remains attached to Launcher while the exact-user WhatsApp activity is
 * visibly resumed. It accepts only a wide WhatsApp-green control in the lower half of the screen;
 * small floating buttons, headers and arbitrary coordinates are deliberately rejected.
 */
object VisualActionButtonPolicy {
    const val MIN_SCREEN_WIDTH = 240
    const val MIN_SCREEN_HEIGHT = 480
    const val SEARCH_TOP_PERCENT = 42
    const val SEARCH_BOTTOM_PERCENT = 97
    const val MIN_GREEN_ROW_PERCENT = 32
    const val MIN_HORIZONTAL_SPAN_PERCENT = 52
    const val MIN_BUTTON_WIDTH_PERCENT = 52

    fun findWidePositiveAction(
        width: Int,
        height: Int,
        pixelAt: (x: Int, y: Int) -> Int
    ): ShizukuBounds? {
        if (width < MIN_SCREEN_WIDTH || height < MIN_SCREEN_HEIGHT) return null

        val step = (minOf(width, height) / 240).coerceIn(2, 6)
        val xStart = width * 4 / 100
        val xEnd = width * 96 / 100
        val yStart = height * SEARCH_TOP_PERCENT / 100
        val yEnd = height * SEARCH_BOTTOM_PERCENT / 100
        val totalXSamples = ((xEnd - xStart) / step).coerceAtLeast(1)

        data class Row(val y: Int, val left: Int, val right: Int)
        data class Band(
            var top: Int,
            var bottom: Int,
            var left: Int,
            var right: Int,
            var rows: Int
        )

        val bands = ArrayList<Band>(4)
        var active: Band? = null
        var y = yStart
        while (y <= yEnd) {
            var greenCount = 0
            var firstGreen = Int.MAX_VALUE
            var lastGreen = Int.MIN_VALUE
            var x = xStart
            while (x <= xEnd) {
                if (isWhatsAppGreen(pixelAt(x, y))) {
                    greenCount += 1
                    if (x < firstGreen) firstGreen = x
                    if (x > lastGreen) lastGreen = x
                }
                x += step
            }

            val row = if (firstGreen != Int.MAX_VALUE && lastGreen > firstGreen &&
                greenCount * 100 >= totalXSamples * MIN_GREEN_ROW_PERCENT &&
                (lastGreen - firstGreen) * 100 >= width * MIN_HORIZONTAL_SPAN_PERCENT
            ) Row(y, firstGreen, lastGreen) else null

            val currentBand = active
            if (row == null) {
                active?.let(bands::add)
                active = null
            } else if (currentBand == null || row.y - currentBand.bottom > step * 2) {
                active?.let(bands::add)
                active = Band(row.y, row.y, row.left, row.right, 1)
            } else {
                currentBand.bottom = row.y
                currentBand.left = minOf(currentBand.left, row.left)
                currentBand.right = maxOf(currentBand.right, row.right)
                currentBand.rows += 1
            }
            y += step
        }
        active?.let(bands::add)

        val minimumHeight = max(18, height / 90)
        return bands.asSequence()
            .filter { band ->
                val bandHeight = band.bottom - band.top + step
                val bandWidth = band.right - band.left + step
                band.rows >= 3 && bandHeight >= minimumHeight &&
                    bandWidth * 100 >= width * MIN_BUTTON_WIDTH_PERCENT
            }
            .maxWithOrNull(compareBy<Band> { it.bottom }.thenBy { (it.right - it.left) * (it.bottom - it.top + step) })
            ?.let { band ->
                ShizukuBounds(
                    left = (band.left - step).coerceAtLeast(1),
                    top = (band.top - step).coerceAtLeast(1),
                    right = (band.right + step).coerceAtMost(width - 1),
                    bottom = (band.bottom + step).coerceAtMost(height - 1)
                )
            }
    }

    fun isWhatsAppGreen(pixel: Int): Boolean {
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        if (green != maximum || green < 62) return false
        if (green - red < 18 || green - blue < 10) return false
        if (maximum == 0 || (maximum - minimum) * 100 / maximum < 18) return false
        return blue * 100 / green <= 92
    }
}
