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

    /**
     * ADIM 5: App Links register-click hedefi. click_id YOKKEN, link-domain
     * uri'sinden register-click icin gereken alanlari cikarir:
     *  - [LinkTarget.host]        E1 domain kontrolu (`*.go.adsparkle.co`) icin.
     *  - [LinkTarget.uniqueKey]   ILK yol segmenti (`/<uniqueKey>?...`) → backend
     *                             ClickEvent'i bu key'den uretir (E2).
     *  - [LinkTarget.queryParams] tum sorgu parametreleri (E2).
     *
     * Opak (non-hierarchical) uri, host'suz veya yol-segmenti olmayan uri → null.
     */
    data class LinkTarget(
        val host: String,
        val uniqueKey: String,
        val queryParams: Map<String, String>,
    )

    fun extractLinkTarget(uri: Uri?): LinkTarget? {
        if (uri == null || uri.isOpaque) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val uniqueKey = uri.pathSegments?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val params = LinkedHashMap<String, String>()
        val names = try {
            uri.queryParameterNames
        } catch (_: UnsupportedOperationException) {
            emptySet<String>()
        }
        for (name in names) {
            val value = try {
                uri.getQueryParameter(name)
            } catch (_: UnsupportedOperationException) {
                null
            }
            if (value != null) params[name] = value
        }
        return LinkTarget(host, uniqueKey, params)
    }
}
