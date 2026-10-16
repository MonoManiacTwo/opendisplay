plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.opendisplay.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.opendisplay.android"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "OpenDisplay Dev")
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "OpenDisplay")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // android.jar only stubs org.json; JVM unit tests need the real thing.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
