plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.deixis.receiver"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        // Приём AirPlay (перенос UxPlay) не входит в сборку для Google Play: ./gradlew -Pdeixis.store=play.
        buildConfigField("boolean", "AIRPLAY", (providers.gradleProperty("deixis.store").orNull != "play").toString())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core"))
    api(libs.libvlc)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.coroutines.android)
    // Окно экрана уходит назад и возвращается по команде пульта (Away.kt).
    implementation(libs.activity.compose)
}
