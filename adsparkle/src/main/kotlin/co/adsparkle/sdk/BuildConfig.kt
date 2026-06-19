package co.adsparkle.sdk

/**
 * Build-time constants. The SDK version string is kept here as a single source of truth
 * so it can be included in outbound request headers without adding a build config field
 * that would complicate the library build type.
 */
internal object BuildConfig {
    const val SDK_VERSION = "0.1.0"
    const val DEBUG       = false
}
