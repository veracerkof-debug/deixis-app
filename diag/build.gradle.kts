import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

// Журнал и диагностика — общие для телефона, ТВ и компьютера: байт-код Java 17, как у ядра.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }
