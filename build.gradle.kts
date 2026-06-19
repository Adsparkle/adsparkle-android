// Root build file — configuration shared across subprojects.
// Do not add any module dependencies here; they belong in the module build files.

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android)  apply false
}
