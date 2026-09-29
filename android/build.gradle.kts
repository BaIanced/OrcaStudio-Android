plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

val orcaBuildRoot: String = providers.gradleProperty("orca.buildRoot")
    .getOrElse("${System.getProperty("user.home")}/build/orca-android")
extra["orcaBuildRoot"] = orcaBuildRoot

// Keep Gradle outputs out of the source tree.
allprojects {
    layout.buildDirectory.set(file("$orcaBuildRoot/gradle/${project.name}"))
}
