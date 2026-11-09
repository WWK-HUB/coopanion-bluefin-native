package com.wwkhub.bluefin

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.IOException

/**
 * Serves packaged assets with explicit MIME types so ES modules load.
 * Android's MimeTypeMap often omits `.js` on older releases, and a
 * `text/plain` response makes `import` fail.
 */
class AssetMimeHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val rel = path.trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        return try {
            val stream = context.assets.open(rel)
            val mime = mimeFor(rel)
            val encoding = if (isText(mime)) "utf-8" else null
            WebResourceResponse(
                mime,
                encoding,
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                stream,
            )
        } catch (_: IOException) {
            null
        }
    }

    private fun isText(mime: String): Boolean {
        return mime.startsWith("text/") || mime.endsWith("/javascript") || mime.endsWith("json")
    }

    private fun mimeFor(path: String): String {
        val name = path.substringBefore('?').lowercase()
        return when {
            name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
            name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".svg") -> "image/svg+xml"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".mp3") -> "audio/mpeg"
            name.endsWith(".ogg") -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }
}
