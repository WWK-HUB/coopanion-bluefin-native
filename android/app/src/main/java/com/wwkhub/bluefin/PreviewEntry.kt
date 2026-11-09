package com.wwkhub.bluefin

object PreviewEntry {
    const val HOST = "https://appassets.androidplatform.net"
    const val ROUTE_B = "bluefin/index.html"
    const val ROUTE_A = "www/bluefin-preview.html"

    /**
     * One-line entry switch, relative to `src/main/assets/`.
     * null uses [ROUTE_B] when `bluefin/index.html` is packaged, otherwise [ROUTE_A]
     * for a local self-test. Delivery builds keep this null and ship Route B.
     */
    val ENTRY_ASSET: String? = null

    fun choose(hasRouteB: Boolean, hasRouteA: Boolean, forced: String? = ENTRY_ASSET): String {
        if (!forced.isNullOrBlank()) return forced
        if (hasRouteB) return ROUTE_B
        if (hasRouteA) return ROUTE_A
        return ROUTE_B
    }

    fun urlFor(assetPath: String): String = "$HOST/assets/$assetPath"

    fun isSelfTest(assetPath: String): Boolean = assetPath == ROUTE_A
}
