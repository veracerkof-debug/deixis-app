import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

/** Платформа библиотек FFmpeg: та, на которой идёт сборка (каждый установщик собирается на своей системе). */
val javacppPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

/** Платформа Skia для Compose (окно пульта): та же, на которой идёт сборка. */
val composePlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
    when {
        os.contains("win") -> "windows-x64"
        os.contains("mac") -> if (arm) "macos-arm64" else "macos-x64"
        else -> if (arm) "linux-arm64" else "linux-x64"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.swing)
    implementation(libs.vlcj)
    implementation(libs.pdfbox)
    implementation(libs.imageio.webp)
    implementation(libs.imageio.tiff)
    // Встроенный перевод PPTX и PPT в PDF, без LibreOffice (Apache-2.0).
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)
    implementation(libs.sqlite.jdbc)
    // Трансляция экрана с малой задержкой: декодер FFmpeg напрямую (сборка bytedeco под LGPL), кадр — сразу на экран.
    // В установщик идут библиотеки только той платформы, на которой он собирается.
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(javacppPlatform) })
    runtimeOnly(variantOf(libs.javacpp) { classifier(javacppPlatform) })
    testRuntimeOnly(variantOf(libs.ffmpeg) { classifier(javacppPlatform) })
    runtimeOnly(libs.slf4j.nop)
    // Пульт на компьютере — тот же, что на телефоне: Compose Multiplatform (Apache-2.0), Skia своей платформы.
    implementation(libs.compose.desktop.runtime)
    implementation(libs.compose.desktop.foundation)
    implementation(libs.compose.desktop.ui)
    implementation(libs.compose.desktop.animation)
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$composePlatform:${libs.versions.composeDesktop.get()}")

    testImplementation(libs.kotlin.test)
}

val macExports = if (System.getProperty("os.name").lowercase().contains("mac")) listOf("--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED") else emptyList()

application {
    mainClass.set("app.deixis.desktop.MainKt")
    applicationName = "deixis"
    // На Маке — ключ для полного экрана системы (MacScreen).
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8") + macExports
}

// Номер версии из метки выпуска (DEIXIS_VERSION) вшивается в программу: по нему сравниваются обновления.
val versionResource = layout.buildDirectory.dir("generated/version")
val writeVersion by tasks.registering {
    val v = System.getenv("DEIXIS_VERSION")?.removePrefix("v") ?: "0.1.0"
    inputs.property("version", v)
    outputs.dir(versionResource)
    doLast { versionResource.get().file("deixis-version.txt").asFile.apply { parentFile.mkdirs(); writeText(v) } }
}
sourceSets.main { resources.srcDir(writeVersion) }
// Пульт — тот же, что на телефоне (pult/): на компьютере к нему добавляется своя платформа (app.deixis.phone в desktop).
kotlin.sourceSets["main"].kotlin.srcDir("../pult/src/main/kotlin")

// Демо-набор первого запуска — те же файлы, что у телефона, и их список (папку в jar не перечислить).
val demoAssets = rootProject.file("android/phone/src/main/assets")
val demoIndex = layout.buildDirectory.dir("generated/demo")
val writeDemoIndex by tasks.registering {
    inputs.dir(demoAssets.resolve("demo"))
    outputs.dir(demoIndex)
    doLast {
        val names = demoAssets.resolve("demo").listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
        demoIndex.get().file("demo/index.txt").asFile.apply { parentFile.mkdirs(); writeText(names.joinToString("\n")) }
    }
}
sourceSets.main { resources.srcDir(demoAssets); resources.srcDir(writeDemoIndex) }

tasks.test {
    useJUnitPlatform()
    jvmArgs(macExports)
    systemProperty("deixis.compat", rootProject.file("testdata/compat").path)
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Имена файлов с кириллицей (демо, папки Holyrics) под локалью C превращаются в «?????» — только на Linux.
    if (System.getProperty("os.name").orEmpty().lowercase().contains("linux")) {
        environment("LC_ALL", "C.UTF-8")
        environment("LANG", "C.UTF-8")
    }
    // Проверка настоящих окон (CI на macOS, Windows и Linux с xvfb): -Ddeixis.gui=1.
    System.getProperty("deixis.gui")?.let {
        systemProperty("deixis.gui", it)
        // Каждая проверка окон — в своём процессе: окна, полный экран Mac и VLC одной не влияют на другую.
        forkEvery = 1
    }
    // Для ручной проверки раскладок мониторов: -Ddeixis.outputsPng=папка сохраняет кадры в PNG.
    System.getProperty("deixis.shots")?.let { systemProperty("deixis.shots", it) }
    System.getProperty("deixis.outputsPng")?.let { systemProperty("deixis.outputsPng", it) }
    System.getProperty("deixis.trayPng")?.let { systemProperty("deixis.trayPng", it) }
}
