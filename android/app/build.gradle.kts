import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val orcaBuildRoot = rootProject.extra["orcaBuildRoot"] as String
val orcaSrc = rootProject.file("../src-orca")

// Upstream OrcaSlicer version and commit, shown in the app's about section (see UPSTREAM.md).
val orcaVersion: String = Regex("""SoftFever_VERSION\s+"([^"]+)"""")
    .find(File(orcaSrc, "version.inc").readText())?.groupValues?.get(1) ?: "unknown"
val orcaCommit: String = providers.exec {
    commandLine("git", "-C", orcaSrc.path, "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "unknown" }
val sourceUrl: String = providers.gradleProperty("orca.sourceUrl")
    .getOrElse("http://192.168.178.3:3002/daniel/OrcaSlicer-Android")

android {
    namespace = "com.orcaslicer.android"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.orcaslicer.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The native core is only built for arm64.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "ORCA_VERSION", "\"$orcaVersion\"")
        buildConfigField("String", "ORCA_COMMIT", "\"$orcaCommit\"")
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a release build can be installed directly on the tablet.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The vendor archives are already deflated.
        noCompress += "zip"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/** Runs android/scripts/pack_resources.py to turn src-orca/resources into app assets. */
abstract class PackOrcaAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: ConfigurableFileCollection

    @get:Input
    abstract val resourcesRoot: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tabCpp: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val translations: ConfigurableFileCollection

    @get:Input
    abstract val translationsRoot: Property<String>

    @get:InputFile
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun pack() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        execOps.exec {
            commandLine(
                "python3", script.get().asFile.path,
                "--resources", resourcesRoot.get(),
                "--assets", out.path,
                "--tab-cpp", tabCpp.get().asFile.path,
                "--i18n", translationsRoot.get(),
            )
        }
    }
}

val packOrcaAssets = tasks.register<PackOrcaAssets>("packOrcaAssets") {
    val res = File(orcaSrc, "resources")
    resourcesRoot.set(res.path)
    resources.from(fileTree(res) {
        include("profiles/**", "info/**", "flush/**", "filament_mixing/**", "printers/**", "custom_gcodes/**", "shapes/**")
    })
    script.set(rootProject.file("scripts/pack_resources.py"))
    tabCpp.set(File(orcaSrc, "src/slic3r/GUI/Tab.cpp"))
    val i18n = File(orcaSrc, "localization/i18n")
    translationsRoot.set(i18n.path)
    translations.from(fileTree(i18n) { include("*/*.po") })
    // The layout extractor is a second script the packer runs.
    inputs.file(rootProject.file("scripts/extract_settings_layout.py"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packOrcaAssets, PackOrcaAssets::outputDir)
        // android/build.sh stages the stripped liborca_jni.so here.
        variant.sources.jniLibs?.addStaticSourceDirectory("$orcaBuildRoot/jniLibs")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
