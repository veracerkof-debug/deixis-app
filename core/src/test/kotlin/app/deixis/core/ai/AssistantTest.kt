package app.deixis.core.ai

import app.deixis.core.effects.SceneLooks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun op(s: String) = SceneOp.parse(obj(s))!!
    private val base = SceneLooks.load("evening")
    private val firstLayer get() = base.layers.first().id

    @Test
    fun sceneRoundTripsThroughTheTree() {
        val out = SceneOps.applyAll(base, emptyList())
        assertNotNull(out.scene)
        assertEquals(base.layers.map { it.id }, out.scene!!.layers.map { it.id })
        assertEquals(base.seed, out.scene!!.seed)
    }

    @Test
    fun setsParametersAndDescribesBeforeAfter() {
        val out = SceneOps.applyAll(base, listOf(op("""{"op":"scene.set","key":"quiet","value":0.6}"""), op("""{"op":"layer.set","id":"$firstLayer","set":{"on":false}}""")))
        assertNull(out.errors.firstOrNull())
        assertEquals(0.6, out.scene!!.quiet, 1e-9)
        assertFalse(out.scene!!.layers.first().on)
        assertTrue(out.lines.first().contains("→"), out.lines.toString())
        assertFalse(out.dangerous)
    }

    @Test
    fun layersMoveDuplicateAndRemoveAreDangerousOnlyForRemove() {
        val n = base.layers.size
        val dup = SceneOps.applyAll(base, listOf(op("""{"op":"layer.duplicate","id":"$firstLayer","newId":"copy1"}"""), op("""{"op":"layer.move","id":"copy1","to":0}""")))
        assertEquals("copy1", dup.scene!!.layers.first().id)
        assertEquals(n + 1, dup.scene!!.layers.size)
        assertFalse(dup.dangerous)
        val rm = SceneOps.applyAll(base, listOf(op("""{"op":"layer.remove","id":"$firstLayer"}""")))
        assertTrue(rm.dangerous)
        assertEquals(n - 1, rm.scene!!.layers.size)
    }

    @Test
    fun badOperationsAreRefusedWithPaths() {
        val unknown = SceneOps.applyAll(base, listOf(op("""{"op":"show.slide"}""")))
        assertNull(unknown.scene); assertTrue(unknown.errors.first().contains("неизвестная операция"))
        val nolayer = SceneOps.applyAll(base, listOf(op("""{"op":"layer.set","id":"nope","set":{"on":true}}""")))
        assertTrue(nolayer.errors.first().contains("нет слоя"))
        val badKey = SceneOps.applyAll(base, listOf(op("""{"op":"scene.set","key":"layers","value":[]}""")))
        assertNull(badKey.scene)
        // потолок формата держит проверка итога: слоёв не больше 12
        val many = (1..15).map { op("""{"op":"layer.duplicate","id":"$firstLayer","newId":"c$it"}""") }
        val over = SceneOps.applyAll(base, many)
        assertNull(over.scene); assertTrue(over.errors.isNotEmpty())
        // исходная сцена не тронута
        assertEquals("evening", base.id)
    }

    @Test
    fun bindingsAndEffectsEditAndUnbind() {
        val l = base.layers.first { it.type == "shape" }
        val out = SceneOps.applyAll(base, listOf(
            op("""{"op":"layer.bind","layer":"${l.id}","param":"size","src":"energy","amt":0.2}"""),
            op("""{"op":"effect.add","layer":"${l.id}","effect":{"type":"blur","p":{"r":4}}}"""),
        ))
        assertNull(out.errors.firstOrNull(), out.errors.toString())
        val made = out.scene!!.layer(l.id)!!
        assertEquals("energy", made.bind["size"]?.src)
        assertTrue(made.fx.any { it.type == "blur" })
        val back = SceneOps.applyAll(out.scene!!, listOf(op("""{"op":"layer.unbind","layer":"${l.id}","param":"size"}"""), op("""{"op":"effect.remove","layer":"${l.id}","index":${made.fx.size - 1}}""")))
        assertNull(back.errors.firstOrNull(), back.errors.toString())
        assertNull(back.scene!!.layer(l.id)!!.bind["size"])
    }

    @Test
    fun proposalWaitsAcceptsPartiallyAndUndoes() {
        val e = ProposalEngine(base)
        val r = e.propose("Тише и спокойнее", listOf(op("""{"op":"scene.set","key":"quiet","value":0.8}"""), op("""{"op":"scene.set","key":"speed","value":0.5}""")))
        val p = (r as Proposed.Ok).proposal
        assertFalse(r.autoApplied)
        assertEquals(base.quiet, e.scene.quiet, 1e-9) // пока не принято — без изменений
        assertEquals(ProposalState.PENDING, p.state)
        assertTrue(e.accept(p.id, only = setOf(0)).isEmpty())
        assertEquals(0.8, e.scene.quiet, 1e-9)
        assertEquals(base.speed, e.scene.speed, 1e-9) // вторая операция не выбрана
        assertTrue(e.undoLast())
        assertEquals(base.quiet, e.scene.quiet, 1e-9)
        assertFalse(e.undoLast())
        assertEquals(listOf("Предложение уже не ждёт ответа"), e.accept(p.id))
    }

    @Test
    fun autoConfirmSkipsOnlyOrdinaryProposals() {
        val e = ProposalEngine(base).also { it.autoConfirm = true }
        val ok = e.propose("тише", listOf(op("""{"op":"scene.set","key":"quiet","value":0.7}""")))
        assertTrue((ok as Proposed.Ok).autoApplied); assertEquals(0.7, e.scene.quiet, 1e-9); assertTrue(e.journal.single().auto)
        val danger = e.propose("убрать", listOf(op("""{"op":"layer.remove","id":"$firstLayer"}""")))
        assertFalse((danger as Proposed.Ok).autoApplied)
        assertTrue(danger.proposal.dangerous)
        assertEquals(base.layers.size, e.scene.layers.size, "опасное не применяется само")
        assertEquals(1, e.pending().size)
        e.reject(danger.proposal.id)
        assertEquals(0, e.pending().size)
    }

    @Test
    fun limitsReadOnlyRateAndExpiry() {
        var t = 0L
        val e = ProposalEngine(base) { t }
        val one = listOf(op("""{"op":"scene.set","key":"quiet","value":0.5}"""))
        assertTrue(e.propose("a", List(51) { one[0] }) is Proposed.Rejected)
        repeat(4) { assertTrue(e.propose("p$it", one) is Proposed.Ok) }
        assertTrue(e.propose("пятое", one) is Proposed.Rejected, "не больше 4 в минуту")
        t += 61_000
        val late = e.propose("после паузы", one) as Proposed.Ok
        t += ProposalEngine.TTL_MS + 1
        assertTrue(e.pending().isEmpty()); assertEquals(ProposalState.EXPIRED, late.proposal.state)
        e.readOnly = true
        assertTrue(e.propose("x", one) is Proposed.Rejected)
    }

    @Test
    fun applyingOneProposalExpiresStaleOnes() {
        val e = ProposalEngine(base)
        val a = (e.propose("a", listOf(op("""{"op":"scene.set","key":"quiet","value":0.5}"""))) as Proposed.Ok).proposal
        val b = (e.propose("b", listOf(op("""{"op":"scene.set","key":"speed","value":0.7}"""))) as Proposed.Ok).proposal
        assertTrue(e.accept(a.id).isEmpty())
        assertEquals(ProposalState.EXPIRED, b.state)
    }

    @Test
    fun assistantReadsFreelyFixesItsMistakeAndNeverTouchesTheHall() {
        val e = ProposalEngine(base)
        val bad = ToolCall("propose_change", obj("""{"summary":"ошибка","ops":[{"op":"layer.set","id":"nope","set":{"on":false}}]}"""))
        val good = ToolCall("propose_change", obj("""{"summary":"Тише","ops":[{"op":"scene.set","key":"quiet","value":0.9}]}"""))
        val show = ToolCall("show.slide")
        val provider = ScriptedProvider(listOf(
            ModelTurn.Calls(listOf(ToolCall("scene.get"), ToolCall("scene.preflight"), show)),
            ModelTurn.Calls(listOf(bad)),
            ModelTurn.Calls(listOf(good)),
            ModelTurn.Say("Предложил сделать тише"),
        ))
        val reply = Assistant(e, provider, "инструкция").ask("Сделай фон тише")
        assertEquals("Предложил сделать тише", reply.text)
        assertEquals(1, reply.proposals.size)
        assertEquals(base.quiet, e.scene.quiet, 1e-9, "без «Принять» ничего не меняется")
        val data = provider.seen.last().filter { it.role == "data" }.map { it.text }
        assertTrue(data.any { it.startsWith("Отказ") && it.contains("нет слоя") }, "ошибка возвращена модели")
        assertTrue(data.any { it.startsWith("Нет такого инструмента «show.slide»") }, "показ в зал вне каталога")
    }

    @Test
    fun assistantGivesUpAfterRepeatedFailures() {
        val bad = ToolCall("propose_change", obj("""{"ops":[{"op":"zzz"}]}"""))
        val provider = ScriptedProvider(List(10) { ModelTurn.Calls(listOf(bad)) })
        val reply = Assistant(ProposalEngine(base), provider, "x").ask("сломай")
        assertTrue(reply.text.startsWith("Не получилось"))
        assertTrue(provider.calls <= 5)
    }
}

class SceneEditorTest {
    private val bg = app.deixis.core.SceneBackground("evening")

    @Test
    fun editsCopyTheLookIntoACustomSceneAndTheScreenReadsIt() {
        val first = SceneEditor.sceneOf(bg)!!.layers.first().id
        val off = SceneEditor.toggle(bg, first, false)!!
        assertNotNull(off.scene)
        assertNotEquals(bg.key, off.key)
        val read = app.deixis.core.effects.SceneText.sceneFor(off)!!
        assertFalse(read.layers.first().on)
        // готовая сцена не изменилась
        assertTrue(SceneLooks.load("evening").layers.first().on)
        val moved = SceneEditor.move(off, first, 1)!!
        assertEquals(first, app.deixis.core.effects.SceneText.sceneFor(moved)!!.layers[1].id)
        val dup = SceneEditor.duplicate(moved, first)!!
        assertEquals(read.layers.size + 1, app.deixis.core.effects.SceneText.sceneFor(dup)!!.layers.size)
        val rm = SceneEditor.remove(dup, first)!!
        assertEquals(read.layers.size, app.deixis.core.effects.SceneText.sceneFor(rm)!!.layers.size)
    }

    @Test
    fun invalidResultKeepsTheBackgroundAndBrokenJsonShowsNothing() {
        val ids = SceneEditor.sceneOf(bg)!!
        assertNull(SceneEditor.remove(bg, "no-such-layer"))
        assertNull(app.deixis.core.effects.SceneText.sceneFor(bg.copy(scene = "{не json")))
        assertNotNull(ids)
    }
}

class AssistantPromptTest {
    @Test
    fun resourceCopyMatchesTheDocument() {
        val doc = java.io.File("../docs/ai/SCENE_PROMPT.md")
        assertTrue(AssistantPrompt.text.length > 500, "инструкция должна лежать в ресурсах")
        if (doc.exists()) assertEquals(doc.readText(), AssistantPrompt.text, "обновите core/src/main/resources/ai/scene_prompt.md из docs/ai/SCENE_PROMPT.md")
    }

    @Test
    fun withSceneRoundTrips() {
        val bg = app.deixis.core.SceneBackground("evening")
        val s = SceneLooks.load("evening")
        val out = SceneEditor.withScene(bg, s)
        assertEquals(s.layers.map { it.id }, app.deixis.core.effects.SceneText.sceneFor(out)!!.layers.map { it.id })
    }
}

class SceneEditorParamTest {
    @Test
    fun setParamChangesOnlyThatLayerAndClampsToTheCatalog() {
        val bg = app.deixis.core.SceneBackground("evening")
        val id = SceneEditor.sceneOf(bg)!!.layers.first { it.type == "shape" }.id
        val out = SceneEditor.setParam(bg, id, "opacity", 0.4)!!
        assertEquals(0.4, app.deixis.core.effects.SceneText.sceneFor(out)!!.layer(id)!!.num("opacity"), 1e-9)
        val over = SceneEditor.setParam(bg, id, "opacity", 7.0)!!
        assertEquals(1.0, app.deixis.core.effects.SceneText.sceneFor(over)!!.layer(id)!!.num("opacity"), 1e-9)
    }
}

class SongTargetTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun op(s: String) = SceneOp.parse(obj(s))!!
    private val song = app.deixis.core.SongText.parse("[Куплет 1]\nГосподь мой Пастырь\nЯ не буду нуждаться\n\n[Припев]\nСлава Ему")

    @Test
    fun editsSlidesAndDescribesBeforeAfter() {
        val out = SongTarget.applyAll(song, listOf(
            op("""{"op":"slide.update","index":0,"text":"Господь — мой Пастырь\nЯ не буду нуждаться"}"""),
            op("""{"op":"slide.add","label":"Бридж","text":"Аллилуйя","at":1}"""),
            op("""{"op":"slide.move","index":2,"to":0}"""),
        ))
        assertTrue(out.errors.isEmpty(), out.errors.toString())
        assertEquals(listOf("Припев", "Куплет 1", "Бридж"), out.state!!.map { it.label })
        assertTrue(out.lines.first().contains("→"))
        assertFalse(out.dangerous)
        // исходный список не тронут
        assertEquals("Куплет 1", song.first().label)
    }

    @Test
    fun deletingAndReplacingAreDangerousAndBadInputIsRefused() {
        assertTrue(SongTarget.applyAll(song, listOf(op("""{"op":"slide.delete","index":1}"""))).dangerous)
        assertTrue(SongTarget.applyAll(song, listOf(op("""{"op":"song.replace","text":"[А]\nб"}"""))).dangerous)
        assertNull(SongTarget.applyAll(song, listOf(op("""{"op":"slide.delete","index":9}"""))).state)
        assertNull(SongTarget.applyAll(song, listOf(op("""{"op":"slide.update","index":0,"text":"${"я".repeat(2001)}"}"""))).state)
        assertNull(SongTarget.applyAll(song, listOf(op("""{"op":"scene.set","key":"quiet","value":1}"""))).state)
    }

    @Test
    fun theSameEngineAndAssistantWorkOnSongText() {
        val e = ProposalEngine(song, SongTarget)
        val provider = ScriptedProvider(listOf(
            ModelTurn.Calls(listOf(ToolCall("song.get"), ToolCall("scene.get"))),
            ModelTurn.Calls(listOf(ToolCall("propose_change", obj("""{"summary":"Исправил","ops":[{"op":"slide.update","index":1,"label":"Хор"}]}""")))),
            ModelTurn.Say("Готово"),
        ))
        val reply = Assistant(e, provider, "инструкция", SongTarget).ask("поправь подпись")
        assertEquals(1, reply.proposals.size)
        assertEquals("Припев", e.state[1].label, "пока не принято — без изменений")
        assertTrue(e.accept(reply.proposals.single().id).isEmpty())
        assertEquals("Хор", e.state[1].label)
        assertTrue(e.undoLast()); assertEquals("Припев", e.state[1].label)
        val seen = provider.seen.last().filter { it.role == "data" }.map { it.text }
        assertTrue(seen.any { it.startsWith("[0] Куплет 1") })
        assertTrue(seen.any { it.startsWith("Нет такого инструмента «scene.get»") }, "инструменты сцены песне недоступны")
    }
}

class SceneEditorFxTest {
    @Test
    fun effectsToggleRemoveAndUnbindFromTheEditor() {
        val look = SceneLooks.IDS.first { id -> SceneLooks.load(id).layers.any { it.fx.isNotEmpty() } && SceneLooks.load(id).layers.any { it.bind.isNotEmpty() } }
        val bg = app.deixis.core.SceneBackground(look)
        val scene = SceneEditor.sceneOf(bg)!!
        val withFx = scene.layers.first { it.fx.isNotEmpty() }
        val off = SceneEditor.fxOff(bg, withFx.id, 0, true)!!
        assertTrue(app.deixis.core.effects.SceneText.sceneFor(off)!!.layer(withFx.id)!!.fx[0].off)
        val rm = SceneEditor.fxRemove(off, withFx.id, 0)!!
        assertEquals(withFx.fx.size - 1, app.deixis.core.effects.SceneText.sceneFor(rm)!!.layer(withFx.id)!!.fx.size)
        val bound = scene.layers.first { it.bind.isNotEmpty() }
        val key = bound.bind.keys.first()
        val un = SceneEditor.unbind(bg, bound.id, key)!!
        assertNull(app.deixis.core.effects.SceneText.sceneFor(un)!!.layer(bound.id)!!.bind[key])
    }
}

class SetlistTargetTest {
    private fun op(s: String) = SceneOp.parse(Json.parseToJsonElement(s).jsonObject)!!
    private val lib = listOf(SetEntry("a", "Господь мой Пастырь"), SetEntry("b", "Слава Ему"), SetEntry("c", "Псалом 22"))
    private val target = SetlistTarget(lib)
    private val start = SetlistDraft("Воскресенье", listOf(SetEntry("a", "Господь мой Пастырь")))

    @Test
    fun addsByTitleOrIdMovesRenamesAndRefusesUnknownItems() {
        val out = target.applyAll(start, listOf(op("""{"op":"setlist.add","item":"Слава Ему"}"""), op("""{"op":"setlist.add","item":"c","at":0}"""), op("""{"op":"setlist.rename","title":"Пасха"}"""), op("""{"op":"setlist.move","index":2,"to":0}""")))
        assertTrue(out.errors.isEmpty(), out.errors.toString())
        assertEquals(listOf("b", "c", "a"), out.state!!.items.map { it.id })
        assertEquals("Пасха", out.state!!.title)
        assertFalse(out.dangerous)
        assertNull(target.applyAll(start, listOf(op("""{"op":"setlist.add","item":"Нет такой"}"""))).state)
        assertTrue(target.applyAll(start, listOf(op("""{"op":"setlist.clear"}"""))).dangerous)
    }

    @Test
    fun engineWorksOnSetlistsToo() {
        val e = ProposalEngine(start, target)
        val p = (e.propose("добавить", listOf(op("""{"op":"setlist.add","item":"b"}"""))) as Proposed.Ok).proposal
        assertEquals(1, e.state.items.size)
        assertTrue(e.accept(p.id).isEmpty()); assertEquals(2, e.state.items.size)
        assertTrue(e.undoLast()); assertEquals(1, e.state.items.size)
    }
}
