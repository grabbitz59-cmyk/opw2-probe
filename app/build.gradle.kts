plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val keystoreAlias: String? = System.getenv("KEY_ALIAS")
val keystoreKeyPassword: String? = System.getenv("KEY_PASSWORD")

android {
    namespace = "io.github.smartalarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.smartalarm"
        minSdk = 30
        targetSdk = 34
        versionCode = 24
        versionName = "1.14-passive"
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null && keystoreAlias != null) {
            create("ci") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword ?: keystorePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("ci")
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
