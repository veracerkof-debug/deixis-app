package app.deixis.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Значок трея: на Linux без прозрачных пикселей (там они становились белыми), Δ видна в 16–24 px. */
class TrayIconTest {
    @Test
    fun linuxTrayIconIsOpaqueAndShowsTheMark() {
        for (size in listOf(16, 22, 24, 32)) {
            val img = AppIcon.trayImage(size, opaque = true)
            assertEquals(size, img.width)
            assertTrue(!img.colorModel.hasAlpha())
            // Угол — чёрный (не белая рамка), в середине нижнего края знака — белый.
            assertEquals(0x000000, img.getRGB(0, 0) and 0xFFFFFF)
            val bottom = img.getRGB(size / 2, (size * 89 / 108)) and 0xFFFFFF
            assertTrue((bottom shr 16) > 0xC0, "размер $size: низ Δ белый, а не ${Integer.toHexString(bottom)}")
            System.getProperty("deixis.trayPng")?.let { javax.imageio.ImageIO.write(img, "png", java.io.File(it, "tray-$size.png")) }
        }
    }
}
