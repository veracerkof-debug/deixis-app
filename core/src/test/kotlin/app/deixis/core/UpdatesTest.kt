package app.deixis.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val apk = "новая сборка".toByteArray()

    /** Ответ GitHub в том виде, в каком его отдаёт API (лишние поля пропускаются). */
    private fun json(digest: Boolean = true) = """
        [
          {"tag_name":"v0.10.1","prerelease":true,"draft":false,"body":"- пробная","assets":[
            {"name":"Deixis-0.10.1-all.apk","size":${apk.size},"browser_download_url":"mem://test","digest":"sha256:${sha(apk)}"}]},
          {"tag_name":"v0.10.0","prerelease":false,"draft":false,"html_url":"x","body":"**Что нового:**\n- обновления через GitHub\n- что нового после обновления\n\nТекст","assets":[
            {"name":"Deixis-0.10.0-all.apk","size":${apk.size},"browser_download_url":"mem://all"${if (digest) ",\"digest\":\"sha256:${sha(apk)}\"" else ""}},
            {"name":"Deixis-0.10.0-arm64-v8a.apk","size":${apk.size},"browser_download_url":"mem://arm64","digest":"sha256:${sha(apk)}"},
            {"name":"Deixis-TV-0.10.0-armeabi-v7a.apk","size":1,"browser_download_url":"mem://tv"},
            {"name":"Deixis-1.0.1000.dmg","size":1,"browser_download_url":"mem://dmg"},
            {"name":"Deixis-0.10.0.msi","size":1,"browser_download_url":"mem://msi"},
            {"name":"SHA256SUMS.txt","size":1,"browser_download_url":"mem://sums"}]},
          {"tag_name":"v0.9.2","prerelease":false,"body":"- компьютер не запирает экран","assets":[]},
          {"tag_name":"draft","draft":true,"assets":[]}
        ]
    """.trimIndent()

    private class FakeSource(private val json: String, private val files: Map<String, ByteArray>) : ReleaseSource {
        var fetches = 0
        override suspend fun releases(): List<Release> { fetches++; return parseReleases(json) }
        override suspend fun text(url: String) = files.getValue(url).decodeToString()
        override suspend fun download(url: String, dest: File, onProgress: (Long) -> Unit) {
            dest.writeBytes(files.getValue(url)); onProgress(dest.length())
        }
    }

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(compareVersions("0.10.0", "0.9.2") > 0)
        assertTrue(compareVersions("v1.0", "1.0.0") == 0)
        assertTrue(compareVersions("0.9.2-rc1", "0.9.1") > 0)
        assertNull(parseVersion("draft"))
    }

    @Test
    fun channelsAndAssets() {
        val all = parseReleases(json())
        assertEquals(3, all.size, "черновик и метка не-версии пропущены")
        val stable = latestFor(all, UpdateChannel.STABLE)!!
        assertEquals("0.10.0", stable.version)
        assertEquals("0.10.1", latestFor(all, UpdateChannel.TEST)!!.version)
        assertEquals("Deixis-0.10.0-arm64-v8a.apk", pickAsset(stable, UpdateTarget.PHONE, listOf("arm64-v8a", "armeabi-v7a"))!!.name)
        assertEquals("Deixis-0.10.0-all.apk", pickAsset(stable, UpdateTarget.PHONE, listOf("x86_64"))!!.name)
        // «Deixis-TV-…» не принимается за сборку телефона и наоборот.
        assertEquals("Deixis-TV-0.10.0-armeabi-v7a.apk", pickAsset(stable, UpdateTarget.TV, listOf("armeabi-v7a"))!!.name)
        assertNull(pickAsset(stable, UpdateTarget.TV, listOf("x86")))
        assertEquals("Deixis-0.10.0.msi", pickAsset(stable, UpdateTarget.WINDOWS)!!.name)
        assertEquals("Deixis-1.0.1000.dmg", pickAsset(stable, UpdateTarget.MAC)!!.name)
        assertEquals(sha(apk), pickAsset(stable, UpdateTarget.PHONE)!!.sha256)
    }

    @Test
    fun changelogGivesWhatsNewBetweenVersions() {
        val log = "# Что нового\n\n## 0.10.0\n- обновления\n- окно «Что нового»\n\n## 0.9.2\n- экран не запирается\n\n## 0.9.1\n- подпись v1\n"
        val e = whatsNew(log, "0.9.1", "0.10.0")
        assertEquals(listOf("0.10.0", "0.9.2"), e.map { it.version })
        assertEquals(listOf("обновления", "окно «Что нового»"), e[0].lines)
        assertTrue(whatsNew(log, "0.10.0", "0.10.0").isEmpty())
        assertTrue(bundledChangelog().contains("## 0.9.2"), "журнал вшит в ядро")
    }

    @Test
    fun sumsFile() {
        val m = parseSha256Sums("${"a".repeat(64)}  Deixis-0.10.0.msi\n${"B".repeat(64)} *Deixis-1.0.dmg\nмусор")
        assertEquals("a".repeat(64), m["Deixis-0.10.0.msi"])
        assertEquals("b".repeat(64), m["Deixis-1.0.dmg"])
    }

    private fun updater(source: ReleaseSource, dir: File, installed: MutableList<File>, current: String = "0.9.2") = Updater(
        current, UpdateTarget.PHONE, dir, CoroutineScope(SupervisorJob() + Dispatchers.Default), listOf("arm64-v8a"), source,
    ) { f, _ -> installed += f }

    private fun waitPhase(u: Updater, vararg phases: String) = runBlocking {
        withTimeout(5_000) { while (u.state.value.phase !in phases) delay(10) }
    }

    @Test
    fun manualCheckDoesNotDownloadAndInstallVerifiesTheFile() {
        val dir = createTempDirectory("upd").toFile()
        val src = FakeSource(json(), mapOf("mem://arm64" to apk))
        val installed = mutableListOf<File>()
        val u = updater(src, dir, installed)
        u.start()
        assertEquals(0, src.fetches, "по кнопке: без нажатия в сеть не ходит")
        u.check()
        waitPhase(u, "available", "error")
        assertEquals("available", u.state.value.phase, u.state.value.error)
        assertEquals("0.10.0", u.state.value.available)
        assertEquals(listOf("обновления через GitHub", "что нового после обновления"), u.state.value.notes)
        u.install()
        runBlocking { withTimeout(5_000) { while (installed.isEmpty()) delay(10) } }
        assertEquals(apk.toList(), installed.single().readBytes().toList())
    }

    @Test
    fun brokenDownloadIsRejected() {
        val dir = createTempDirectory("upd").toFile()
        val src = FakeSource(json(), mapOf("mem://arm64" to "подмена".toByteArray()))
        val installed = mutableListOf<File>()
        val u = updater(src, dir, installed)
        u.install()
        waitPhase(u, "error")
        assertTrue(installed.isEmpty(), "файл с неверной суммой не ставится")
        assertTrue(u.state.value.error!!.contains("повреждён") || u.state.value.error!!.contains("полностью"), u.state.value.error)
    }

    @Test
    fun autoModeChecksAndDownloadsButLeavesInstallToThePlatform() {
        val dir = createTempDirectory("upd").toFile()
        val src = FakeSource(json(), mapOf("mem://arm64" to apk))
        val installed = mutableListOf<File>()
        val u = updater(src, dir, installed)
        u.setSettings(UpdateSettings(UpdateMode.AUTO, UpdateChannel.STABLE))
        waitPhase(u, "ready", "error")
        assertEquals("ready", u.state.value.phase, u.state.value.error)
        assertTrue(installed.isEmpty(), "посреди показа само не ставится — решает платформа")
        // Настройки сохраняются между запусками.
        assertEquals(UpdateMode.AUTO, updater(src, dir, installed).state.value.settings.mode)
    }

    @Test
    fun upToDateAndWhatsNewOnce() {
        val dir = createTempDirectory("upd").toFile()
        val u = updater(FakeSource(json(), emptyMap()), dir, mutableListOf(), current = "0.10.0")
        u.check()
        waitPhase(u, "uptodate", "error")
        assertEquals("uptodate", u.state.value.phase, u.state.value.error)
        val log = "## 0.10.0\n- новое\n## 0.9.2\n- старое\n"
        assertTrue(updater(FakeSource(json(), emptyMap()), dir, mutableListOf(), "0.9.2").takeWhatsNew(log).isEmpty(), "установка с нуля — без окна")
        assertEquals(listOf("0.10.0"), updater(FakeSource(json(), emptyMap()), dir, mutableListOf(), "0.10.0").takeWhatsNew(log).map { it.version })
        assertTrue(updater(FakeSource(json(), emptyMap()), dir, mutableListOf(), "0.10.0").takeWhatsNew(log).isEmpty(), "второй раз не показывается")
    }

    @Test
    fun missingChecksumUsesSumsFile() {
        val dir = createTempDirectory("upd").toFile()
        val sums = "${sha(apk)}  Deixis-0.10.0-all.apk\n".toByteArray()
        val src = FakeSource(json(digest = false), mapOf("mem://all" to apk, "mem://sums" to sums))
        val installed = mutableListOf<File>()
        val u = Updater("0.9.2", UpdateTarget.PHONE, dir, CoroutineScope(SupervisorJob() + Dispatchers.Default), listOf("x86"), src) { f, _ -> installed += f }
        u.install()
        runBlocking { withTimeout(5_000) { while (installed.isEmpty() && u.state.value.phase != "error") delay(10) } }
        assertEquals(1, installed.size, u.state.value.error)
    }
}
