import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.openlifespan.logger"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.openlifespan.logger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    val signingProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingProperties.getProperty("release.storeFile", "openlifespan-release.keystore"))
            storePassword = signingProperties.getProperty("release.storePassword")
            keyAlias = signingProperties.getProperty("release.keyAlias")
            keyPassword = signingProperties.getProperty("release.keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
