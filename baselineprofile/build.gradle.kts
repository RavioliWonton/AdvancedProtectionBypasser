// Test-only module that generates the app's Baseline Profile and Startup
// Profile. It is never part of the app's runtime graph; `:app` consumes it via
// the `baselineProfile(project(":baselineprofile"))` dependency.
//
// Generate with:
//   ./gradlew :app:generateBaselineProfile
// which runs the journeys below on the Gradle Managed Device declared here.
plugins {
    alias(libs.plugins.android.test)
    // Creates the generateBaselineProfile task and the nonMinifiedRelease /
    // benchmarkRelease variants it profiles.
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "wonton.abp.baselineprofile"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The module whose release variant is profiled.
    targetProjectPath = ":app"
    // Required by the Baseline Profile plugin: the test module instruments the
    // target app, not itself.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    testOptions.managedDevices.localDevices {
        // google-atd is the lightweight automated-test image: it boots much
        // faster than a full image, which matters for the release workflow.
        // The CI job rewrites apiLevel to the newest google-atd image whose API
        // level does not exceed the app's compileSdk.
        create("pixel6Atd") {
            device = "Pixel 6"
            apiLevel = 36
            systemImageSource = "google-atd"
            // The ATD images are x86_64 only and do not support NDK translation;
            // AGP defaults to x86_64 today but warns because AGP 10 will switch
            // the default to arm64-v8a.
            testedAbi = "x86_64"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    managedDevices += "pixel6Atd"
    // CI and local runs use the managed device above, never a plugged-in phone.
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
