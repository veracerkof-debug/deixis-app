// Зеркало Maven Central от Google: основной адрес Maven Central часто отвечает 429.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(centralMirror)
        google()
        mavenCentral()
    }
}

rootProject.name = "deixis"

include(":core", ":desktop")

// Android-модули собираются только там, где есть Android SDK (CI, Android Studio).
// DEIXIS_SKIP_ANDROID=1 отключает их явно (задание CI для ядра и компьютера).
val hasAndroidSdk = file("local.properties").exists() ||
    System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
if (hasAndroidSdk && System.getenv("DEIXIS_SKIP_ANDROID") == null) include(":android:receiver", ":android:tv", ":android:phone")
