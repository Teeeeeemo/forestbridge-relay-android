fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val relayUiToken = providers.gradleProperty("FORESTBRIDGE_UI_TOKEN")
    .orElse(providers.environmentVariable("FORESTBRIDGE_UI_TOKEN"))
    .orElse("")
    .get()

plugins {
    id("com.android.application")
}

android {
    namespace = "com.forestbridge.relay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.forestbridge.relay"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        buildConfigField(
            "String",
            "WEB_APP_URL",
            "\"https://robot.wichai.xyz/companion/index.html\""
        )
        buildConfigField(
            "String",
            "RELAY_UI_TOKEN",
            relayUiToken.asBuildConfigString()
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
