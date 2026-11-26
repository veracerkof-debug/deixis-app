package app.deixis.core.effects

import app.deixis.core.DeixisJson
import app.deixis.core.quiz.IssueLevel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneValidatorTest {
    private fun scene(layers: String, extra: String = "") =
        """{"format":"deixis-scene","version":1,$extra"layers":[$layers]}"""

    private val text = """{"type":"text","id":"t"}"""
    private fun codes(json: String) = SceneValidator.validate(json).issues.map { it.code }
    private fun errorCodes(json: String) = SceneValidator.validate(json).errors.map { it.code }

    @Test
    fun minimalSceneGetsDefaults() {
        val r = SceneValidator.validate(scene(text))
        assertTrue(r.ok, r.issues.toString())
        assertEquals(emptyList(), r.issues)
        val l = r.scene!!.layers.single()
        assertEquals("source-over", l.blend)
        assertEquals(5, l.prio)
        assertEquals("cormorant", l.str("font"))
        assertEquals(4.1, l.num("size"), 1e-9)
    }

    @Test
    fun everyBuiltInLookIsValidWithoutAnyRemarks() {
        for (id in SceneLooks.IDS) {
            val raw = SceneLooks::class.java.getResourceAsStream("/effects/looks/$id.deixis-scene.json")!!.readBytes().toString(Charsets.UTF_8)
            val r = SceneValidator.validate(raw)
            assertTrue(r.ok, "$id: ${r.issues}")
            // Готовая сцена — образец: ни ошибок, ни приведённых чисел, ни лишних полей.
            assertEquals(emptyList(), r.issues, id)
        }
    }

    @Test
    fun builtInLooksFollowTheHouseStyle() {
        for (s in SceneLooks.all()) {
            val id = s.id
            assertTrue(s.title.isNotBlank() && s.about.isNotBlank(), "$id: нужны название и описание")
            val bg = s.layers.first()
            assertEquals("gradient", bg.type, "$id: первой идёт подложка")
            assertEquals(5, bg.prio, "$id: подложка не отключается")
            assertEquals("text", s.layers.last().type, "$id: текст последним")
            assertEquals(1, s.layers.count { it.type == "text" }, id)
            assertTrue(s.quiet in 0.2..0.45, "$id: спокойное умолчание «тише»")
            // Один главный образ или вовсе без образа: больше — признак перегруза.
            assertTrue(s.layers.count { it.type == "shape" && it.prio == 5 } <= 1, id)
            // Не мигает: у огоньков и звёзд мерцание ≤ 0,5; эффектов по пикселям нет, всё остальное лёгкое.
            s.layers.filter { it.type == "emitter" }.forEach { assertTrue(it.num("twinkle") <= 0.5, "$id/${it.id}: мерцание") }
            s.layers.forEach { l -> assertTrue(l.fx.none { SceneCatalog.effects[it.type]!!.pixel }, "$id/${l.id}: пиксельный эффект") }
            // Цена укладывается: без ограничений ≤ 12 мс на эталонном кадре и текст всегда остаётся.
            val plan = SceneCost.plan(s, 0.0)
            assertTrue(plan.totalMs <= 12.0, "$id: цена ${plan.totalMs}")
            // Контур смотрит на фигуру этой сцены.
            s.layers.filter { it.type == "contour" }.forEach { assertEquals("shape", s.layer(it.str("src"))?.type, id) }
            // Символ-образ крупный и читаемый, но не закрывает текст: в левой части кадра его нет.
            s.layers.filter { it.type == "shape" && it.prio == 5 }.forEach { assertTrue(it.num("x") >= 0.5 || it.num("copies") > 1, "$id: образ сбоку") }
        }
    }

    @Test
    fun builtInLooksSurviveRoundTrip() {
        for (s in SceneLooks.all()) {
            val encoded = DeixisJson.encodeToString(Scene.serializer(), s)
            val back = SceneValidator.validate(encoded)
            assertTrue(back.ok, "${s.id}: ${back.issues}")
            assertEquals(s, back.scene, s.id)
        }
    }

    @Test
    fun brokenJsonAndFormat() {
        assertEquals(listOf("bad_json"), codes("не json"))
        assertEquals(listOf("bad_json"), codes("[1]"))
        assertTrue("wrong_format" in errorCodes("""{"format":"deixis-quiz","layers":[$text]}"""))
        assertTrue("newer_version" in errorCodes("""{"version":99,"layers":[$text]}"""))
        assertTrue("bad_structure" in errorCodes("""{"layers":"нет"}"""))
        assertTrue("no_layers" in errorCodes("""{"layers":[]}"""))
    }

    @Test
    fun unknownTypeDuplicateIdAndContourSourceAreErrorsWithPaths() {
        val r = SceneValidator.validate(scene(
            """{"type":"magic"},{"type":"text","id":"a"},{"type":"text","id":"a"},{"type":"contour","id":"c","p":{"src":"zzz"}}"""))
        assertFalse(r.ok)
        val byCode = r.errors.associate { it.code to it.path }
        assertEquals("layers[0].type", byCode["unknown_layer_type"])
        assertEquals("layers[2].id", byCode["duplicate_id"])
        assertEquals("layers[3].p.src", byCode["bad_contour_source"])
    }

    @Test
    fun numbersOutOfRangeAreClampedWithAWarning() {
        val r = SceneValidator.validate(scene("""{"type":"shape","id":"s","p":{"size":9,"copies":500,"x":-1}},$text"""))
        assertTrue(r.ok, r.issues.toString())
        val s = r.scene!!.layers[0]
        assertEquals(1.4, s.num("size"), 1e-9)
        assertEquals(64.0, s.num("copies"), 1e-9)
        assertEquals(0.0, s.num("x"), 1e-9)
        assertEquals(3, r.warnings.count { it.code == "out_of_range" })
        assertTrue(r.warnings.any { it.path == "layers[0].p.copies" })
    }

    @Test
    fun wrongTypesAndOptionsAreErrors() {
        val r = SceneValidator.validate(scene("""{"type":"shape","id":"s","blend":"hard","p":{"x":"left","kind":"zebra"}},$text"""))
        val codes = r.errors.map { it.code to it.path }
        assertTrue("bad_number" to "layers[0].p.x" in codes, codes.toString())
        assertTrue("bad_option" to "layers[0].p.kind" in codes, codes.toString())
        assertTrue("bad_option" to "layers[0].blend" in codes, codes.toString())
    }

    @Test
    fun unknownFieldsAreWarningsOnly() {
        val r = SceneValidator.validate(scene("""{"type":"text","id":"t","colour":"red","p":{"colour":"red"}}""", """"extra":1,"""))
        assertTrue(r.ok)
        assertEquals(setOf("extra", "layers[0].colour", "layers[0].p.colour"), r.warnings.map { it.path }.toSet())
    }

    @Test
    fun bindingsAreChecked() {
        val ok = SceneValidator.validate(scene(
            """{"type":"shape","id":"s","bind":{"size":{"src":"beat","amt":0.03},"x":{"expr":"base+sin(t)*0.1*energy"}}},$text"""))
        assertTrue(ok.ok, ok.issues.toString())
        assertEquals(2, ok.scene!!.layers[0].bind.size)

        val badSource = SceneValidator.validate(scene("""{"type":"shape","id":"s","bind":{"size":{"src":"нет","amt":1}}},$text"""))
        assertTrue("unknown_source" in badSource.errors.map { it.code })
        val badExpr = SceneValidator.validate(scene("""{"type":"shape","id":"s","bind":{"size":{"expr":"base+foo("}}},$text"""))
        assertEquals("layers[0].bind.size.expr", badExpr.errors.single { it.code == "bad_expr" }.path)
        val badTarget = SceneValidator.validate(scene("""{"type":"shape","id":"s","bind":{"kind":{"src":"beat"}}},$text"""))
        assertTrue("bad_bind_target" in badTarget.errors.map { it.code })
        val unknownVar = SceneValidator.validate(scene("""{"type":"shape","id":"s","bind":{"size":{"expr":"base*zzz"}}},$text"""))
        assertTrue("bad_expr" in unknownVar.errors.map { it.code })
        // свой источник доступен и в привязке, и в формуле
        val mods = SceneValidator.validate(scene(
            """{"type":"shape","id":"s","bind":{"y":{"src":"lfo1","amt":0.02},"x":{"expr":"base+noise1*0.1"}}},$text""",
            """"mods":[{"id":"lfo1","type":"lfo","rate":0.1},{"id":"noise1","type":"noise","rate":0.3}],"""))
        assertTrue(mods.ok, mods.issues.toString())
    }

    @Test
    fun ceilingsAreErrors() {
        val many = (1..13).joinToString(",") { """{"type":"rays","id":"r$it"}""" }
        assertTrue("too_many_layers" in errorCodes(scene(many)))

        val fx5 = """{"type":"blur"},{"type":"blur"},{"type":"blur"},{"type":"blur"},{"type":"blur"}"""
        assertTrue("too_many_effects" in errorCodes(scene("""{"type":"shape","id":"s","fx":[$fx5]},$text""")))

        val pixel = """{"type":"warp"},{"type":"trails"}"""
        val twoLayers = """{"type":"shape","id":"a","fx":[$pixel]},{"type":"shape","id":"b","fx":[$pixel]},$text"""
        assertTrue("too_many_pixel_effects" in errorCodes(scene(twoLayers)))

        val dust = """{"type":"emitter","id":"e1","p":{"count":200}},{"type":"emitter","id":"e2","p":{"count":200}},{"type":"emitter","id":"e3","p":{"count":10}},$text"""
        assertTrue("too_many_particles" in errorCodes(scene(dust)))

        assertEquals(listOf("unknown_effect_type"), errorCodes(scene("""{"type":"shape","id":"s","fx":[{"type":"glitch"}]},$text""")))
    }

    @Test
    fun paletteAndModulatorsAreChecked() {
        assertTrue("bad_palette" in errorCodes(scene(text, """"palette":["#fff"],""")))
        assertTrue(SceneValidator.validate(scene(text, """"palette":["#111111","#222222","#333333","#444444","#555555"],""")).ok)
        assertTrue("bad_id" in errorCodes(scene(text, """"mods":[{"id":"energy","type":"lfo"}],""")))
        assertTrue("duplicate_id" in errorCodes(scene(text, """"mods":[{"id":"lfo1","type":"lfo"},{"id":"lfo1","type":"lfo"}],""")))
        assertTrue("unknown_mod_type" in errorCodes(scene(text, """"mods":[{"id":"x1","type":"midi"}],""")))
        val nine = (1..9).joinToString(",") { """{"id":"lfo$it","type":"lfo"}""" }
        assertTrue("too_many_mods" in errorCodes(scene(text, """"mods":[$nine],""")))
    }

    @Test
    fun hugeFileIsRejectedBeforeParsing() {
        val big = scene(text, """"about":"${"x".repeat(SceneLimits.MAX_FILE_BYTES)}",""")
        assertEquals(listOf("file_too_big"), codes(big))
    }

    @Test
    fun catalogDescriptionForTheNetworkIsComplete() {
        val j = SceneCatalog.toJson().jsonObject
        val layers = j["layers"]!!.jsonArray.map { it.jsonObject["type"].toString().trim('"') }
        assertEquals(SceneCatalog.layers.keys.toList(), layers)
        assertEquals(SceneCatalog.effects.keys.toList(), j["effects"]!!.jsonArray.map { it.jsonObject["type"].toString().trim('"') })
        assertEquals(SceneLimits.MAX_LAYERS.toString(), j["limits"]!!.jsonObject["layers"].toString())
        assertNotNull(j["formula"]!!.jsonObject["functions"])
        // Каждый параметр, к которому можно привязать источник, помечен в описании.
        val shape = j["layers"]!!.jsonArray.first { it.jsonObject["type"].toString().trim('"') == "shape" }.jsonObject
        val size = shape["params"]!!.jsonArray.first { it.jsonObject["key"].toString().trim('"') == "size" }.jsonObject
        assertEquals("true", size["bindable"].toString())
        assertNull(SceneCatalog.layers["magic"])
    }

    @Test
    fun parameterDefaultsAreInsideTheirOwnRanges() {
        val all = SceneCatalog.layers.values.flatMap { it.params } + SceneCatalog.effects.values.flatMap { it.params }
        for (d in all) {
            if (d.kind == ParamKind.NUM || d.kind == ParamKind.COLOR) assertTrue(d.def in d.min..d.max, "${d.key}: умолчание вне диапазона")
            if (d.kind == ParamKind.SELECT) assertTrue(d.options.any { it.first == d.defText }, "${d.key}: умолчание не из списка")
            if (d.kind == ParamKind.HEX) assertTrue(SceneLimits.HEX.matches(d.defText), d.key)
        }
        assertTrue(IssueLevel.values().isNotEmpty())
    }
}
