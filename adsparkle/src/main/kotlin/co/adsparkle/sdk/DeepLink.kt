package co.adsparkle.sdk

import android.net.Uri

/**
 * Helpers for extracting attribution data from deep-link URIs.
 *
 * The tracking platform appends the click identifier as a query parameter:
 * `myapp://open?click_id=<uuid>` or `https://example.com/path?click_id=<uuid>`.
 */
internal object DeepLink {

    const val CLICK_ID_PARAM = "click_id"

    /**
     * Returns a valid `click_id` query parameter from [uri], or `null` if the
     * uri is null, opaque, missing the parameter, or carries a value that is not
     * a well-formed UUID (parity with adsparkle.js captureFromUrl).
     */
    fun extractClickId(uri: Uri?): String? {
        if (uri == null) return null
        // getQueryParameter throws on opaque (non-hierarchical) URIs.
        if (uri.isOpaque) return null
        val value = try {
            uri.getQueryParameter(CLICK_ID_PARAM)
        } catch (_: UnsupportedOperationException) {
            null
        }
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (Storage.UUID_RE.matches(trimmed)) trimmed else null
    }
}
