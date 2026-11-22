import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

fun envOrDefault(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

val explicitDebugKeystorePath = System.getenv("ANDROID_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val debugKeystoreFile: File = if (explicitDebugKeystorePath != null) {
    val file = File(explicitDebugKeystorePath).let {
        if (it.isAbsolute) it else rootProject.file(explicitDebugKeystorePath)
    }
    require(file.isFile) { "ANDROID_DEBUG_KEYSTORE_PATH does not exist: ${file.absolutePath}" }
    file
} else {
    rootProject.file("debug.keystore")
}

android {
    namespace = "xyz.hidasy.pebblepinmanager.emulatorbridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.hidasy.pebblepinmanager.emulatorbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-emu"
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.isFile) {
                storeFile = debugKeystoreFile
                storePassword = envOrDefault("ANDROID_DEBUG_KEYSTORE_PASSWORD", "android")
                keyAlias = envOrDefault("ANDROID_DEBUG_KEY_ALIAS", "androiddebugkey")
                keyPassword = envOrDefault("ANDROID_DEBUG_KEY_PASSWORD", "android")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pebblekit.server)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
