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

application {
    mainClass.set("app.deixis.desktop.MainKt")
    applicationName = "deixis"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test { useJUnitPlatform() }
