plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Версия из метки релиза (v1.2.3 → 1.2.3); без метки — 0.1.0.
val deixisVersion = System.getenv("DEIXIS_VERSION")?.removePrefix("v") ?: "0.1.0"
val deixisVersionCode = deixisVersion.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10_000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }).coerceAtLeast(1) }

android {
    namespace = "app.deixis.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.deixis.tv"
        minSdk = 26
        targetSdk = 35
        versionName = deixisVersion
        versionCode = deixisVersionCode
    }
    // Ключ подписи берётся из переменных окружения (секреты CI) и никогда не лежит в репозитории.
    val keystore = System.getenv("DEIXIS_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("DEIXIS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DEIXIS_KEY_ALIAS")
            keyPassword = System.getenv("DEIXIS_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    // Отдельный APK под каждый тип процессора (в 3–4 раза меньше) и общий для любого устройства.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":android:receiver"))
    implementation(libs.activity.compose)
}
