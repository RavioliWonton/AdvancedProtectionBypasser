// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // com.android.test is used by :baselineprofile; declaring it here puts AGP
    // on the classpath once so the module can request it versionless-free.
    alias(libs.plugins.android.test) apply false
    // Kotlin support is built into AGP 9+, so the kotlin-android plugin is no
    // longer applied. Only the Compose compiler plugin is needed.
    alias(libs.plugins.kotlin.compose) apply false
    // Validates that every dependency's license is on our allow-list.
    alias(libs.plugins.licensee) apply false
}
