import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

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
    runtimeOnly(libs.slf4j.nop)

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

tasks.test {
    useJUnitPlatform()
    jvmArgs(macExports)
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Проверка настоящих окон (CI на macOS, Windows и Linux с xvfb): -Ddeixis.gui=1.
    System.getProperty("deixis.gui")?.let { systemProperty("deixis.gui", it) }
    // Для ручной проверки раскладок мониторов: -Ddeixis.outputsPng=папка сохраняет кадры в PNG.
    System.getProperty("deixis.outputsPng")?.let { systemProperty("deixis.outputsPng", it) }
}
