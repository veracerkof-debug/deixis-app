package app.deixis.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.deixis.core.SceneBackground
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/** Студия фона рисуется общим кодом пульта: страницы открываются, превью получает кадры, снимки лежат в `-Ddeixis.shots`. */
class StudioRenderTest {
    private val platform = DesktopPlatform(createTempDirectory("studio").toFile(), { _, _ -> error("нет перевода") }, { null }, { false })

    private fun shot(name: String, w: Int = 760, h: Int = 1700, bg: SceneBackground = SceneBackground("evening", 0.6f), tap: androidx.compose.ui.geometry.Offset? = null, body: @androidx.compose.runtime.Composable (SceneBackground, (SceneBackground) -> Unit) -> Unit): Int {
        var changed = 0
        last = null
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CompositionLocalProvider(P provides paletteOf("dark")) {
                Column(Modifier.fillMaxSize().background(P.current.bg).padding(16.dp).verticalScroll(rememberScrollState())) { body(bg) { changed++; last = it } }
            }
        }
        // Кадры превью и образцов считаются в фоне: даём им время и перерисовываем.
        var img = scene.render(0)
        tap?.let {
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, it)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, it)
        }
        for (i in 1..8) { Thread.sleep(250); img = scene.render(i * 250_000_000L) }
        System.getProperty("deixis.shots")?.let { File(it).mkdirs(); File(it, "studio-$name.png").writeBytes(img.encodeToData()!!.bytes) }
        scene.close()
        return changed
    }

    private var last: SceneBackground? = null
    private val evening = app.deixis.core.effects.SceneLooks.load("evening")
    private val shapeId = evening.layers.first { it.type == "shape" }.id

    @Test
    fun allPagesRender() {
        shot("root") { bg, on -> StudioSheet(platform, bg, on) }
        shot("layers") { bg, on -> StudioSheet(platform, bg, on, open = "layers") }
        shot("layer") { bg, on -> StudioSheet(platform, bg, on, open = "layer:$shapeId") }
        shot("add-layer") { bg, on -> StudioSheet(platform, bg, on, open = "add-layer") }
        shot("add-effect") { bg, on -> StudioSheet(platform, bg, on, open = "add-effect:$shapeId") }
        shot("move") { bg, on -> StudioSheet(platform, bg, on, open = "move:$shapeId:size") }
        assertTrue(true)
    }

    /** Касание реально меняет сцену: «Пульс» на странице движения привязывает параметр к такту. */
    @Test
    fun tappingAMotionBindsTheParameter() {
        var scene: androidx.compose.ui.ImageComposeScene? = null
        val got = shot("tap", w = 760, h = 1000, tap = androidx.compose.ui.geometry.Offset(200f, 555f)) { bg, on -> StudioSheet(platform, bg, on, open = "move:$shapeId:size") }
        assertTrue(got > 0, "касание не изменило сцену")
        val b = app.deixis.core.effects.SceneText.sceneFor(last!!)!!.layer(shapeId)!!.bind["size"]
        assertTrue(b?.src == "beat", "ожидался пульс, а привязка: $b")
        assertTrue(scene == null)
    }
}
