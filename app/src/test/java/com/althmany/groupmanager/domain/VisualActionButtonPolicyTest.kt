package com.althmany.groupmanager.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualActionButtonPolicyTest {
    @Test
    fun `detects wide WhatsApp green action with text holes`() {
        val width = 720
        val height = 1544
        val pixels = IntArray(width * height) { 0xfff7f8fa.toInt() }
        paint(pixels, width, 38, 1340, 682, 1432, 0xff008069.toInt())
        // Simulate the light label cutting holes through the green control.
        paint(pixels, width, 260, 1372, 460, 1402, 0xffffffff.toInt())

        val found = VisualActionButtonPolicy.findWidePositiveAction(width, height) { x, y ->
            pixels[y * width + x]
        }

        assertNotNull(found)
        assertTrue(found!!.centerX in 300..420)
        assertTrue(found.centerY in 1350..1425)
    }

    @Test
    fun `rejects WhatsApp floating action button`() {
        val width = 720
        val height = 1544
        val pixels = IntArray(width * height) { 0xffffffff.toInt() }
        paint(pixels, width, 620, 1350, 700, 1430, 0xff25d366.toInt())

        val found = VisualActionButtonPolicy.findWidePositiveAction(width, height) { x, y ->
            pixels[y * width + x]
        }
        assertNull(found)
    }

    @Test
    fun `rejects wide non-green controls`() {
        val width = 720
        val height = 1544
        val pixels = IntArray(width * height) { 0xffffffff.toInt() }
        paint(pixels, width, 38, 1340, 682, 1432, 0xff1677ff.toInt())

        val found = VisualActionButtonPolicy.findWidePositiveAction(width, height) { x, y ->
            pixels[y * width + x]
        }
        assertNull(found)
    }

    private fun paint(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int
    ) {
        for (y in top until bottom) {
            for (x in left until right) pixels[y * width + x] = color
        }
    }
}
