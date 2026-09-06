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
}
