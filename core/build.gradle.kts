import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

// Ядро общее для телефона, ТВ и компьютера, поэтому байт-код Java 17: его понимает Android.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.jmdns)
    implementation(libs.zxing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    useJUnitPlatform()
    // Имена файлов с кириллицей (папки Holyrics) читаются верно только в UTF-8.
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
    // Для ручной проверки: -Ddeixis.dumpStream=файл сохраняет поток зрителя (fMP4).
    System.getProperty("deixis.dumpStream")?.let { systemProperty("deixis.dumpStream", it) }
    // Для ручной проверки перевода PPTX: -Ddeixis.render=папка сохраняет слайды в PNG.
    System.getProperty("deixis.render")?.let { systemProperty("deixis.render", it) }
    System.getProperty("deixis.renderFile")?.let { systemProperty("deixis.renderFile", it) }
    System.getProperty("deixis.pptDump")?.let { systemProperty("deixis.pptDump", it) }
    testLogging {
        showStandardStreams = System.getProperty("deixis.renderFile") != null
        // Полный след падения прямо в журнале CI: отчёты тестов оттуда не всегда достать.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Журнал изменений вшит в приложения: «Что нового» после обновления видно и без сети.
tasks.processResources {
    from(rootProject.file("CHANGELOG.md")) { rename { "changelog.md" } }
}

// Данные прошлых версий (testdata/compat): новая сборка обязана их прочитать.
tasks.test { systemProperty("deixis.compat", rootProject.file("testdata/compat").path) }
