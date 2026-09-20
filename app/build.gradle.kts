import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties
import netboot.build.BuildGoAarTask
import netboot.build.VerifyGoAarTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Share the pinned NDK with gomobile, which does not read android.ndkVersion.
val pinnedNdkVersion = "28.2.13676358"

val knownAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/** One ABI selection controls gomobile, APK splits and AAR verification. */
val netbootAbis: List<String> = providers.gradleProperty("netbootAbis")
    .get()
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .also { abis ->
        require(abis.isNotEmpty()) { "netbootAbis must list at least one ABI" }
        require(abis.all { it in knownAbis }) { "netbootAbis contains an unsupported ABI: $abis" }
    }
val emulatorAbis = listOf("x86", "x86_64")

/** CI supplies the release tag; local builds default to 1.0.0. */
val netbootVersionName: String = providers.gradleProperty("netbootVersionName").getOrElse("1.0.0")

/** gomobile inverts the x86 family naming: Gradle's `x86_64` is Go's `amd64`. */
fun gomobileTargetFor(abi: String): String = when (abi) {
    "arm64-v8a" -> "android/arm64"
    "armeabi-v7a" -> "android/arm"
    "x86_64" -> "android/amd64"
    "x86" -> "android/386"
    else -> error("unsupported ABI: $abi")
}

android {
    namespace = "com.sky22333.netboot"
    compileSdk = 37
    ndkVersion = pinnedNdkVersion

    defaultConfig {
        applicationId = "com.sky22333.netboot"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = netbootVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += netbootAbis }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*netbootAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // AAPT requires b+zh+Hans; keep it aligned with locales_config.xml.
        localeFilters += listOf("en", "b+zh+Hans")
        noCompress += listOf("efi", "kpxe", "ipxe")
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Prefer local.properties for SDK discovery, matching AGP.

val localPropertiesFile = rootProject.layout.projectDirectory.file("local.properties")

fun readLocalProperties(): Properties = Properties().apply {
    val file = localPropertiesFile.asFile
    if (file.isFile) file.inputStream().use { load(it) }
}

val androidSdkDir: Provider<String> = providers.provider {
    readLocalProperties().getProperty("sdk.dir")?.takeIf(String::isNotBlank)
        ?: providers.environmentVariable("ANDROID_HOME").orNull?.takeIf(String::isNotBlank)
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull?.takeIf(String::isNotBlank)
        ?: throw GradleException(
            "Android SDK location not found. Set sdk.dir in ${localPropertiesFile.asFile} " +
                "or export ANDROID_HOME/ANDROID_SDK_ROOT.",
        )
}

val androidNdkDir: Provider<String> = androidSdkDir.map { sdk -> File(sdk, "ndk/$pinnedNdkVersion").absolutePath }

val javaHomeDir: Provider<String> = providers.provider {
    providers.environmentVariable("JAVA_HOME").orNull?.takeIf(String::isNotBlank)
        ?: providers.systemProperty("java.home").orNull?.takeIf(String::isNotBlank)
        ?: throw GradleException("No JDK available for gomobile: set JAVA_HOME.")
}

val goExecutable: Provider<String> = providers.environmentVariable("GO_EXECUTABLE").orElse("go")

val goAarFile = layout.projectDirectory.file("libs/netboot-core.aar")

val buildGoAar = tasks.register<BuildGoAarTask>("buildGoAar") {
    group = "build"
    description = "Binds core-go/mobilecore into the gomobile AAR consumed by the app"
    goSourceDirectory.set(layout.projectDirectory.dir("../core-go"))
    goModFile.set(layout.projectDirectory.file("../core-go/go.mod"))
    goSumFile.set(layout.projectDirectory.file("../core-go/go.sum"))
    aarOutput.set(layout.buildDirectory.file("outputs/goaar/netboot-core.aar"))
    executablePath.set(goExecutable)
    sdkDirectory.set(androidSdkDir)
    ndkDirectory.set(androidNdkDir)
    javaHome.set(javaHomeDir)
    inheritedPath.set(
        providers.environmentVariable("Path").orElse(providers.environmentVariable("PATH")).orElse(""),
    )
    targetAbis.set(netbootAbis.joinToString(",") { gomobileTargetFor(it) })
    androidApiLevel.set(26)
    javaPackage.set("com.sky22333.netboot.core")
    goPackage.set("./mobilecore")
}

val verifyGoAar = tasks.register<VerifyGoAarTask>("verifyGoAar") {
    group = "verification"
    description = "Asserts the gomobile AAR covers the selected ABIs and installs it into app/libs"
    aarInput.set(buildGoAar.flatMap { it.aarOutput })
    installedAar.set(goAarFile)
    requiredAbis.set(netbootAbis)
    // Emulator ABIs must be explicitly requested.
    forbiddenAbis.set(emulatorAbis.filterNot(netbootAbis::contains))
}

tasks.named("preBuild") {
    dependsOn(verifyGoAar)
}

dependencies {
    implementation(files(goAarFile))
    implementation(platform(libs.compose.bom))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.manifest)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
