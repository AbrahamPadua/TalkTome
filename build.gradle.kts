plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere: AGP 9 compiles Kotlin itself; this only pins the Kotlin version it uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
