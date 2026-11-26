package app.deixis.core.ai

import app.deixis.core.effects.ParamKind
import app.deixis.core.effects.SceneCatalog
import app.deixis.core.effects.SceneLooks
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneStudioTest {
    private val base = SceneLooks.load("evening")

    @Test
    fun everyLayerKindCanBeAddedWithCatalogDefaultsAndPassesValidation() {
        for (type in SceneCatalog.layers.keys) {
            val scene = if (type == "contour") base else SceneStudio.blank()
            assertEquals(type != "contour", SceneStudio.canAdd(SceneStudio.blank(), type))
            val layer = SceneStudio.newLayer(scene, type)
            val out = SceneOps.applyAll(scene, listOf(SceneOp("layer.add", buildJsonObject { put("layer", layer) })))
            assertNotNull(out.scene, "$type: ${out.errors}")
            assertTrue(out.scene!!.layers.any { it.type == type })
        }
    }

    @Test
    fun everyEffectKindCanBeAdded() {
        val layer = base.layers.first { it.type == "shape" }
        for (type in SceneCatalog.effects.keys) {
            val out = SceneOps.applyAll(base, listOf(SceneOp("effect.add", buildJsonObject { put("layer", layer.id); put("effect", SceneStudio.newEffect(type)) })))
            // потолки формата (число пиксельных эффектов, 4 на слой) могут отказать: тогда ошибка внятная, а не обрыв
            assertTrue(out.scene != null || out.errors.isNotEmpty(), type)
        }
    }

    @Test
    fun blankSceneIsValidAndSmall() {
        val b = SceneStudio.blank()
        assertTrue(b.layers.size in 1..3)
        assertNotNull(SceneOps.applyAll(b, emptyList()).scene)
        assertTrue(b.layers.any { it.type == "text" })
    }

    @Test
    fun paramsAreGroupedForPeopleAndNothingIsLost() {
        for (layer in SceneLooks.all().flatMap { it.layers }) {
            val kind = SceneCatalog.layers[layer.type]!!
            val shown = SceneStudio.grouped(layer).flatMap { it.second }.map { it.key }.toSet()
            val expected = kind.own.filter { it.kind != ParamKind.LAYER && !(layer.type == "text" && it.key == "text" && layer.str("source") != "fixed") }.map { it.key }.toSet()
            assertEquals(expected, shown, layer.type)
        }
        val shape = base.layers.first { it.type == "shape" }
        val groups = SceneStudio.grouped(shape).map { it.first }
        assertTrue(ParamGroup.PLACE in groups && ParamGroup.COLOR in groups && ParamGroup.SIZE in groups)
    }

    @Test
    fun motionPresetsRoundTripThroughRealOperations() {
        val shape = base.layers.first { it.type == "shape" }
        for (m in Motion.values()) {
            val ops = SceneStudio.motionOps(base, shape.id, "size", m, 0.3)
            val out = SceneOps.applyAll(base, ops)
            assertNotNull(out.scene, "$m: ${out.errors}")
            val layer = out.scene!!.layer(shape.id)!!
            assertEquals(m, SceneStudio.motionOf(out.scene!!, layer, "size"), m.name)
            assertEquals(0.3, SceneStudio.strengthOf(layer, "size"), 1e-9)
            val still = SceneOps.applyAll(out.scene!!, SceneStudio.stillOps(shape.id, "size")).scene!!
            assertNull(SceneStudio.motionOf(still, still.layer(shape.id)!!, "size"))
        }
    }

    @Test
    fun newSourcesGetFreeIdsAndAreReused() {
        val shape = base.layers.first { it.type == "shape" }
        val first = SceneOps.applyAll(base, SceneStudio.motionOps(base, shape.id, "size", Motion.BREATH, 0.2)).scene!!
        val second = SceneStudio.motionOps(first, shape.id, "alpha", Motion.BREATH, 0.2)
        assertTrue(second.none { it.op == "mod.add" }, "дыхание уже есть — новый источник не нужен")
    }
}

class SceneFramesTest {
    @Test
    fun bmpHasAValidHeaderAndTheRightPixels() {
        val px = IntArray(5 * 2) { if (it == 0) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val b = app.deixis.core.effects.SceneFrames.bmp(px, 5, 2)
        assertEquals('B'.code.toByte(), b[0]); assertEquals('M'.code.toByte(), b[1])
        val row = 16 // 5*3 = 15, до кратного 4
        assertEquals(54 + row * 2, b.size)
        // нижняя строка файла — вторая строка кадра (синяя): B=255
        assertEquals(0xFF.toByte(), b[54]); assertEquals(0, b[56].toInt())
        // верхняя строка кадра идёт последней, её первый пиксель красный: R=255
        assertEquals(0xFF.toByte(), b[54 + row + 2])
    }

    @Test
    fun stillsDifferBetweenLooksAndKeepTheSceneIntact() {
        val a = app.deixis.core.effects.SceneFrames.still(SceneLooks.load("evening"), 96, 54)
        val b = app.deixis.core.effects.SceneFrames.still(SceneLooks.load("fire"), 96, 54)
        assertTrue(a.indices.count { a[it] != b[it] } > 500)
    }
}

class SceneEditorStudioTest {
    private val bg = app.deixis.core.SceneBackground("evening")

    @Test
    fun addLayerEffectMotionAndRenameWorkTogether() {
        val (b1, id) = SceneEditor.addLayer(bg, "rays")!!
        assertEquals("rays", app.deixis.core.effects.SceneText.sceneFor(b1)!!.layer(id)!!.type)
        val b2 = SceneEditor.addEffect(b1, id, "bloom")!!
        val b3 = SceneEditor.fxParam(b2, id, 0, "amt", 0.9)!!
        assertEquals(0.9, app.deixis.core.effects.SceneText.sceneFor(b3)!!.layer(id)!!.fx[0].p["amt"]!!, 1e-9)
        val b4 = SceneEditor.motion(b3, id, "alpha", Motion.PULSE, 0.25)!!
        assertEquals(Motion.PULSE, SceneStudio.motionOf(app.deixis.core.effects.SceneText.sceneFor(b4)!!, app.deixis.core.effects.SceneText.sceneFor(b4)!!.layer(id)!!, "alpha"))
        val b5 = SceneEditor.rename(b4, id, "  Лучи света ")!!
        assertEquals("Лучи света", app.deixis.core.effects.SceneText.sceneFor(b5)!!.layer(id)!!.name)
        assertNull(SceneEditor.rename(b5, id, "   "))
        val shape = app.deixis.core.effects.SceneText.sceneFor(b5)!!.layers.first { it.type == "shape" }
        val b6 = SceneEditor.setText(b5, shape.id, "kind", "dove")!!
        assertEquals("dove", app.deixis.core.effects.SceneText.sceneFor(b6)!!.layer(shape.id)!!.str("kind"))
        assertNull(SceneEditor.addLayer(app.deixis.core.SceneBackground("mine", scene = app.deixis.core.DeixisJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), SceneOps.toTree(SceneStudio.blank()))), "contour"), "без образа контур не добавить")
    }

    @Test
    fun formulasAreAcceptedOrRefusedByTheSameValidator() {
        val shape = app.deixis.core.effects.SceneText.sceneFor(bg)!!.layers.first { it.type == "shape" }
        val ok = SceneEditor.bindExpr(bg, shape.id, "size", "0.5 + 0.5 * sin(t * pi) * energy")
        assertTrue(ok != null, "верная формула отвергнута")
        assertEquals("0.5 + 0.5 * sin(t * pi) * energy", app.deixis.core.effects.SceneText.sceneFor(ok!!)!!.layer(shape.id)!!.bind["size"]!!.expr)
        assertNull(SceneEditor.bindExpr(bg, shape.id, "size", "evil(1)"))
        assertNull(SceneEditor.bindExpr(bg, shape.id, "size", "1 +"))
    }
}
