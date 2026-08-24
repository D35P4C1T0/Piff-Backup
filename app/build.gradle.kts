plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.d35p4c1t0.piffbackup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.d35p4c1t0.piffbackup"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ndkVersion = "28.2.13676358"

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            // The Phase 1 executables must be extracted to nativeLibraryDir so
            // ProcessBuilder can execute them. This is intentionally APK-first.
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libpiffbackup_*.so")
        }
    }
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
