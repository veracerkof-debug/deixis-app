package app.deixis.core.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneCostTest {
    private fun scene(layers: String) = SceneValidator.validate("""{"layers":[$layers]}""").also { assertTrue(it.ok, it.issues.toString()) }.scene!!

    @Test
    fun mostExpensiveLowPriorityGoFirstAndProtectedLayersStay() {
        val s = scene(
            """{"id":"bg","type":"gradient"},
               {"id":"paint","type":"field","prio":4,"p":{"count":8}},
               {"id":"cross","type":"shape","prio":5,"p":{"kind":"cross","glow":20,"avoid":0.5}},
               {"id":"wave","type":"contour","prio":3,"p":{"src":"cross"}},
               {"id":"rays","type":"rays","prio":2,"p":{"avoid":0.8}},
               {"id":"dust","type":"emitter","prio":1,"p":{"count":40}},
               {"id":"t","type":"text"}""")
        val none = SceneCost.plan(s, 0.0)
        assertEquals(emptyList(), none.dropped)
        assertTrue(none.totalMs > 8)

        // Бюджет жёсткий: уходят по приоритету 1, 2, 3, 4. Защищённые (подложка, текст, образ с приоритетом 5) остаются.
        val tiny = SceneCost.plan(s, 1.0)
        assertEquals(listOf("dust", "rays", "wave", "paint"), tiny.dropped)
        assertTrue(tiny.keptMs > 0)
        assertTrue("bg" !in tiny.dropped && "cross" !in tiny.dropped && "t" !in tiny.dropped)

        // Мягкий бюджет: хватает убрать только самое малозначимое.
        val total = none.totalMs
        val mild = SceneCost.plan(s, total - 0.2)
        assertEquals(listOf("dust"), mild.dropped)
    }

    @Test
    fun calibrationScalesTheEstimate() {
        val s = SceneLooks.load("joy")
        val base = SceneCost.plan(s, 8.0, calibration = 1.0)
        val slow = SceneCost.plan(s, 8.0, calibration = 2.5) // устройство втрое медленнее эталона
        assertTrue(slow.dropped.size > base.dropped.size, "${base.dropped} против ${slow.dropped}")
        val fast = SceneCost.plan(s, 8.0, calibration = 0.2)
        assertEquals(emptyList(), fast.dropped)
    }

    @Test
    fun largerSoftResolutionCostsMoreOnlyForSoftLayers() {
        val contour = SceneLooks.load("evening").layer("wave")!!
        val shape = SceneLooks.load("evening").layer("cross")!!
        assertTrue(SceneCost.layerCost(contour, 320) > SceneCost.layerCost(contour, 192) * 2.5)
        assertEquals(SceneCost.layerCost(shape, 320), SceneCost.layerCost(shape, 192), 1e-9) // чёткий слой от разрешения не зависит
        assertTrue(SceneCost.softScale(96) < 0.3)
    }

    @Test
    fun effectsAndAvoidAddCost() {
        val plain = scene("""{"id":"s","type":"shape"}""").layers[0]
        val withFx = scene("""{"id":"s","type":"shape","fx":[{"type":"bloom"},{"type":"warp"}]}""").layers[0]
        val off = scene("""{"id":"s","type":"shape","fx":[{"type":"bloom","off":true}]}""").layers[0]
        assertTrue(SceneCost.layerCost(withFx) > SceneCost.layerCost(plain) + 4.5)
        assertEquals(SceneCost.layerCost(plain), SceneCost.layerCost(off), 1e-9) // выключенный эффект ничего не стоит
        val avoid = scene("""{"id":"s","type":"shape","p":{"avoid":0.5}}""").layers[0]
        assertEquals(SceneCost.layerCost(plain) + 1.2, SceneCost.layerCost(avoid), 1e-9)
    }

    @Test
    fun strictModeKeepsOnlyItsLayers() {
        val s = SceneLooks.load("joy")
        val strict = s.strictLayers().map { it.type }
        assertEquals(listOf("gradient", "field", "shape", "text"), strict)
        val full = SceneCost.plan(s, 0.0, strict = false).totalMs
        val lite = SceneCost.plan(s, 0.0, strict = true).totalMs
        assertTrue(lite < full / 2, "строгий режим заметно дешевле: $lite против $full")
    }

    @Test
    fun disabledLayersAreIgnored() {
        val s = scene("""{"id":"a","type":"field","on":false},{"id":"t","type":"text"}""")
        assertEquals(SceneCost.layerCost(s.layers[1]), SceneCost.plan(s, 0.0).totalMs, 1e-9)
    }
}
