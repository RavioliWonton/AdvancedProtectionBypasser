plugins {
    alias(libs.plugins.android.application)
    // Kotlin support is built into AGP 9+, so the kotlin-android plugin is no
    // longer applied. Only the Compose compiler plugin is needed.
    alias(libs.plugins.kotlin.compose)
    // Fails the build if a dependency uses a license not on the allow-list.
    alias(libs.plugins.licensee)
}

android {
    namespace = "wonton.abp"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "wonton.abp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // All values come from environment variables so no secret ever
            // lands in the repo. CI populates them from GitHub Actions secrets;
            // locally they are simply absent and the release build stays unsigned.
            val storePath = System.getenv("KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the signing config when a keystore was provided,
            // otherwise Gradle would fail configuring an empty signing config.
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // With built-in Kotlin (AGP 9+), kotlin.compilerOptions.jvmTarget defaults
    // to compileOptions.targetCompatibility (17), so no kotlinOptions block is
    // needed.

    buildFeatures {
        compose = true
    }
}

// License validation for the whole runtime dependency graph.
// Run `./gradlew :app:licensee` (also wired into `check`).
// Reports land in app/build/reports/licensee/<variant>/.
licensee {
    // AndroidX, Kotlin, kotlinx-coroutines and libxposed are all Apache-2.0.
    allow("Apache-2.0")
    // libxposed service/interface declare their license via a plain GitHub URL
    // instead of an SPDX id. The repo (github.com/libxposed) is Apache-2.0.
    allowUrl("https://github.com/libxposed/service/blob/master/LICENSE") {
        because("libxposed is licensed Apache-2.0; the POM just links to the raw LICENSE file")
    }
}

dependencies {
    // libxposed: API is provided by the framework at runtime, so compileOnly.
    compileOnly(libs.libxposed.api)
    // Service lib is bundled to talk to the manager (remote prefs / scope).
    implementation(libs.libxposed.service)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    // Material 3 (version managed by the Compose BOM; Expressive APIs are stable).
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    debugImplementation(libs.androidx.ui.tooling)
}
