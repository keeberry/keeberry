plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied: AGP builds Kotlin itself. Listing it puts this Kotlin version on the build
    // classpath, which AGP then uses, so it matches the Compose compiler plugin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
