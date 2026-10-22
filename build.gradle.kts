// Android Gradle Plugin подключается, только когда собираются Android-модули (условие как в settings.gradle.kts):
// без Android SDK и доступа к Google Maven ядро и приложение для компьютера собираются отдельно.
buildscript {
    val androidEnabled = System.getenv("DEIXIS_SKIP_ANDROID") == null && (
        file("local.properties").exists() || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
        )
    if (androidEnabled) {
        repositories {
            google()
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
            mavenCentral()
        }
        dependencies { classpath("com.android.tools.build:gradle:8.11.1") }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
