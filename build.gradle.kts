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

// Имена файлов читаются в кодировке локали: без UTF-8 кириллические имена демо-файлов не видны,
// и сборка падает позже с невнятной ошибкой про хэш файла. Проверяем только на Linux: на Windows и macOS
// имена идут в Юникоде независимо от sun.jnu.encoding.
val fileNameEncoding = System.getProperty("sun.jnu.encoding").orEmpty()
if (System.getProperty("os.name").orEmpty().startsWith("Linux") && fileNameEncoding.isNotEmpty() && !fileNameEncoding.startsWith("UTF", ignoreCase = true)) {
    throw GradleException(
        "Кодировка имён файлов: $fileNameEncoding. В проекте есть файлы с русскими именами — " +
            "запустите сборку в UTF-8 локали: LC_ALL=C.UTF-8 LANG=C.UTF-8 ./gradlew ..."
    )
}
