plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.smartalarm"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.smartalarm"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.health:health-services-client:1.1.0")
    implementation("com.google.guava:guava:32.1.3-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
