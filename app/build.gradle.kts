plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties

// Release signing credentials live outside version control in
// keystore.properties (see keystore.properties.template)
val keystoreProperties = Properties().apply {
    // The master signing key lives at the workspace level (../signing),
    // outside the app repositories; a repo-local file still wins if present.
    val f = rootProject.file("keystore.properties")
        .takeIf { it.exists() } ?: rootProject.file("../signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// versionCode: git commit count (monotonic, unique).
fun gitVersionCode(): Int = try {
    val out = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim()
    maxOf(out.toIntOrNull() ?: 0, 1)
} catch (e: Exception) {
    1
}

fun gitTagName(): String = try {
    // `|| true`: a tagless repo must not fail the build.
    providers.exec {
        commandLine("sh", "-c", "git describe --tags --abbrev=0 2>/dev/null || true")
        workingDir = rootProject.projectDir
    }.standardOutput.asText.get().trim().removePrefix("v")
} catch (e: Exception) {
    ""
}

val appVersionName: String =
    (project.findProperty("versionName") as String?) ?: gitTagName().ifEmpty { "1.0" }

android {
    namespace = "com.isaklab.isdrdrivers"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.isaklab.isdrdrivers"
        minSdk = 24
        targetSdk = 36
        versionCode = gitVersionCode()
        versionName = appVersionName
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    // One APK per architecture instead of one carrying all four. A phone runs
    // exactly one ABI: the universal package shipped ~89 MB of native code no
    // device would ever load, and the ONNX runtime alone is ~29 MB per ABI.
    // Debug keeps every ABI so the x86_64 emulators still install.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // This app ships its GPL sources verbatim; shrinking would only
            // obscure the correspondence between APK and published source.
            isMinifyEnabled = false
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        // JVM harness tests drive the real network clients against the local
        // radio emulators; android.util.Log / os.Process become no-ops.
        unitTests.isReturnDefaultValues = true
    }
    lint {
        disable += setOf(
            "ObsoleteSdkInt",
            "OldTargetApi",
            "GradleDependency",
            "NewerVersionAvailable",
            "UseTomlInstead",
            "UseKtx",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":proto"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // FFT Library for the drivers' display spectrum path
    implementation("com.github.wendykierp:JTransforms:3.1")

    testImplementation(libs.junit)
}
