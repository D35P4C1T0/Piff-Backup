plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

val buildVersionCode = providers.environmentVariable("PIFFBACKUP_VERSION_CODE")
    .orElse("1")
    .get()
    .toInt()
val buildVersionName = providers.environmentVariable("PIFFBACKUP_VERSION_NAME")
    .orElse("1.0")
    .get()

require(buildVersionCode > 0) { "PIFFBACKUP_VERSION_CODE must be positive" }
require(buildVersionName.isNotBlank()) { "PIFFBACKUP_VERSION_NAME must not be blank" }

android {
    namespace = "com.d35p4c1t0.piffbackup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.d35p4c1t0.piffbackup"
        minSdk = 33
        targetSdk = 37
        versionCode = buildVersionCode
        versionName = buildVersionName

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
        buildConfig = true
    }
    packaging {
        jniLibs {
            // The bundled executables must be extracted to nativeLibraryDir so
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

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.sshj)
    implementation(libs.slf4j.nop)
    ksp(libs.androidx.room3.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room3.testing)
}
