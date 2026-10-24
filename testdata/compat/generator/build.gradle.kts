plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
dependencies { implementation(project(":core")); implementation(project(":desktop")) }
application { mainClass.set("app.deixis.phone.FixKt") }
