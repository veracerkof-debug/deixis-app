package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Кнопка «Сохранить» и её сёстры реально рисуются: у акцентной кнопки есть заливка, у выключенной она блёкнет
 * вся целиком (раньше слабел только текст, а заливка оставалась яркой). Рендер — тем же общим кодом, что на телефоне.
 */
class BtnRenderTest {
    private val density = 2f

    private fun render(theme: String, body: @androidx.compose.runtime.Composable () -> Unit): org.jetbrains.skia.Bitmap {
        val scene = ImageComposeScene(width = 900, height = 700, density = Density(density)) {
            CompositionLocalProvider(P provides paletteOf(theme)) {
                Column(Modifier.fillMaxSize().background(P.current.bg).padding(16.dp)) { body() }
            }
        }
        val img = scene.render(0)
        System.getProperty("deixis.shots")?.let { File(it, "btn-$theme.png").writeBytes(img.encodeToData()!!.bytes) }
        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
        scene.close()
        return bmp
    }

    private fun org.jetbrains.skia.Bitmap.rgb(x: Int, y: Int): Int = getColor(x, y) and 0xFFFFFF
    private fun dist(a: Int, b: Int) = abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))

    @Test
    fun tintButtonIsFilledAndDisabledOneFadesAsAWhole() {
        for (theme in listOf("light", "dark")) {
            val pal = paletteOf(theme)
            val bmp = render(theme) {
                Btn(L.save, kind = BtnKind.TINT) {}
                Btn(L.save, kind = BtnKind.TINT, enabled = false) {}
            }
            val tint = pal.tint.toArgb() and 0xFFFFFF
            val bg = pal.bg.toArgb() and 0xFFFFFF
            // Кнопка высотой 50 dp: первая — первые 100 px от поля 32 px, вторая — следующие. Берём точку у левого края, вне текста.
            val enabledPx = bmp.rgb(80, 32 + 50)
            val disabledPx = bmp.rgb(80, 32 + 100 + 50)
            assertTrue(dist(enabledPx, tint) < 12, "$theme: у акцентной кнопки нет заливки: ${Integer.toHexString(enabledPx)} вместо ${Integer.toHexString(tint)}")
            assertTrue(dist(disabledPx, bg) < dist(enabledPx, bg) * 0.75, "$theme: выключенная кнопка не поблёкла целиком: ${Integer.toHexString(disabledPx)}")
            assertTrue(dist(disabledPx, tint) > 12, "$theme: выключенная кнопка осталась яркой")
        }
    }
}
