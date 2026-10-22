plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere: AGP 9 compiles Kotlin itself; this only pins the Kotlin version it uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Optional: build outside a synced folder (OneDrive locks build files). Set LIVINGBOOK_BUILD_ROOT=C:/some/local/dir.
System.getenv("LIVINGBOOK_BUILD_ROOT")?.let { root ->
    allprojects { layout.buildDirectory.set(file("$root/${rootProject.name}/${project.name}")) }
}
