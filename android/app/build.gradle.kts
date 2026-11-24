import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

val explicitReleaseKeystorePath = System.getenv("ANDROID_RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystoreFile: File? = explicitReleaseKeystorePath?.let { path ->
    val file = File(path).let {
        if (it.isAbsolute) it else rootProject.file(path)
    }
    require(file.isFile) { "ANDROID_RELEASE_KEYSTORE_PATH does not exist: ${file.absolutePath}" }
    file
}

val resolvedVersionCode: Int = System.getenv("ANDROID_VERSION_CODE")
    ?.takeIf { it.isNotBlank() }
    ?.toIntOrNull()
    ?: 1
val resolvedVersionName: String = System.getenv("ANDROID_VERSION_NAME")
    ?.takeIf { it.isNotBlank() }
    ?: "0.1.0"

android {
    namespace = "xyz.hidasy.pebblepinmanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.hidasy.pebblepinmanager"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = requireNotNull(
                    System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() },
                ) { "ANDROID_RELEASE_KEYSTORE_PASSWORD is required when ANDROID_RELEASE_KEYSTORE_PATH is set" }
                keyAlias = requireNotNull(
                    System.getenv("ANDROID_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() },
                ) { "ANDROID_RELEASE_KEY_ALIAS is required when ANDROID_RELEASE_KEYSTORE_PATH is set" }
                keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "DEV_MODE_AVAILABLE", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "DEV_MODE_AVAILABLE", "false")
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("releaseDev") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "DEV_MODE_AVAILABLE", "true")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pebblekit.client)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
