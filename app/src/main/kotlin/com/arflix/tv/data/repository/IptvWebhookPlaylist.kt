package com.arflix.tv.data.repository

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal data class IptvWebhookCredentials(
    val username: String,
    val password: String,
    // The Xtream server base URL (e.g. "http://format.com" or "http://format.com:8080").
    // Mandatory in the webhook response (key "url" preferred, also accepts "host"/"server").
    val url: String,
)

internal data class IptvWebhookCatalogVodRef(
    val kind: String,
    val streamId: Int,
    val ext: String,
)

internal object IptvWebhookPlaylist {
    // IPTV-WEBHOOK 1: core object + fixed endpoint.
    // The URL is part of the contract; changing it requires updating callers and docs.
    const val ENDPOINT = "https://hooks.932426.xyz/webhook/db2b991a-1dd2-46f2-9b7d-a167183fdb44"

    const val SOURCE_ID = "list_1"
    const val SOURCE_NAME = "Source"
    const val CATALOG_VOD_SCHEME = "xtream-vod"

    // IPTV-WEBHOOK 1.1: parseResponse supports both {"matchedItem": {...}} and flat object.
    // "url" (preferred) or "host"/"server" provides the Xtream server base URL.
    fun parseResponse(json: String): IptvWebhookCredentials {
        val root = JsonParser.parseString(json).asJsonObject
        val item = when {
            root.has("matchedItem") && root.get("matchedItem").isJsonObject ->
                root.getAsJsonObject("matchedItem")
            else -> root
        }
        val username = firstNonBlank(
            item,
            "username",
            "user",
            "uname",
        ) ?: throw IllegalStateException("Webhook response is missing username")
        val password = firstNonBlank(
            item,
            "password",
            "pass",
            "pwd",
        ) ?: throw IllegalStateException("Webhook response is missing password")
        val url = firstNonBlank(
            item,
            "url",
            "host",
            "server",
            "m3uUrl",
            "playlist",
            "playlistUrl",
        ) ?: firstNonBlank(root, "url", "host", "server", "m3uUrl")
            ?: throw IllegalStateException("Webhook response is missing url (or host/server)")

        if (url.isBlank()) {
            throw IllegalStateException("Webhook response url is blank")
        }
        return IptvWebhookCredentials(username = username, password = password, url = url)
    }

    fun normalizeHost(host: String): String? {
        val h = host.trim().trimEnd('/')
        if (h.isBlank()) return null
        val withScheme = when {
            h.startsWith("http://", ignoreCase = true) || h.startsWith("https://", ignoreCase = true) -> h
            else -> "http://$h"
        }
        return withScheme.toHttpUrlOrNull()?.let { parsed ->
            buildString {
                append(parsed.scheme)
                append("://")
                append(parsed.host)
                val defaultPort = if (parsed.scheme == "https") 443 else 80
                if (parsed.port != defaultPort) append(":${parsed.port}")
            }
        } ?: withScheme.substringBefore('?').trimEnd('/')
            .removeSuffix("/get.php")
            .removeSuffix("/xmltv.php")
            .removeSuffix("/player_api.php")
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
    }

    fun hostScopedCacheKey(baseUrl: String): String =
        normalizeHost(baseUrl).orEmpty().lowercase(Locale.US)

    // IPTV-WEBHOOK 1.2: catalog identity only — never embed username/password.
    fun catalogSource(host: String): IptvPlaylistEntry {
        val base = normalizeHost(host) ?: throw IllegalStateException("Webhook host is blank")
        return IptvPlaylistEntry(
            id = SOURCE_ID,
            name = SOURCE_NAME,
            m3uUrl = base,
            epgUrl = "",
            enabled = true,
            epgUrls = emptyList(),
        )
    }

    fun catalogLiveUrl(baseUrl: String, streamId: Int): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/live/$streamId.ts"
    }

    fun catalogMovieUrl(streamId: Int, ext: String): String =
        "$CATALOG_VOD_SCHEME://movie/$streamId.${sanitizeExt(ext)}"

    fun catalogSeriesUrl(streamId: Int, ext: String): String =
        "$CATALOG_VOD_SCHEME://series/$streamId.${sanitizeExt(ext)}"

    fun isCatalogVodUrl(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        return trimmed.startsWith("$CATALOG_VOD_SCHEME://", ignoreCase = true)
    }

    fun parseCatalogVodUrl(url: String): IptvWebhookCatalogVodRef? {
        val trimmed = url.trim()
        val prefix = "$CATALOG_VOD_SCHEME://"
        if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
        val rest = trimmed.substring(prefix.length)
        val kind = rest.substringBefore('/').lowercase(Locale.US)
        if (kind != "movie" && kind != "series") return null
        val file = rest.substringAfter('/', missingDelimiterValue = "")
        val streamId = file.substringBefore('.').toIntOrNull() ?: return null
        val ext = file.substringAfter('.', missingDelimiterValue = "mp4").ifBlank { "mp4" }
        return IptvWebhookCatalogVodRef(kind = kind, streamId = streamId, ext = sanitizeExt(ext))
    }

    fun buildLiveUrl(baseUrl: String, username: String, password: String, streamId: Int): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/live/$username/$password/$streamId.ts"
    }

    fun buildMovieUrl(baseUrl: String, username: String, password: String, streamId: Int, ext: String): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/movie/$username/$password/$streamId.${sanitizeExt(ext)}"
    }

    fun buildSeriesUrl(baseUrl: String, username: String, password: String, streamId: Int, ext: String): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/series/$username/$password/$streamId.${sanitizeExt(ext)}"
    }

    fun buildPlayerApiUrl(baseUrl: String, username: String, password: String, action: String): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/player_api.php?username=$username&password=$password&action=$action"
    }

    fun buildXmltvUrl(baseUrl: String, username: String, password: String): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/xmltv.php?username=$username&password=$password"
    }

    fun rewriteCatalogLiveUrl(catalogUrl: String, username: String, password: String, streamId: Int? = null): String? {
        val parsed = catalogUrl.trim().toHttpUrlOrNull() ?: return null
        val base = parsed.toString().substringBefore('?').trimEnd('/')
            .substringBefore("/live/")
            .trimEnd('/')
        val id = streamId ?: parsed.pathSegments.lastOrNull()
            ?.substringBefore('.')
            ?.toIntOrNull()
            ?: return null
        return buildLiveUrl(base, username, password, id)
    }

    fun rewriteCatalogVodUrl(
        catalogUrl: String,
        baseUrl: String,
        username: String,
        password: String,
    ): String? {
        val ref = parseCatalogVodUrl(catalogUrl) ?: return null
        return when (ref.kind) {
            "series" -> buildSeriesUrl(baseUrl, username, password, ref.streamId, ref.ext)
            else -> buildMovieUrl(baseUrl, username, password, ref.streamId, ref.ext)
        }
    }

    fun looksLikeCredentialedXtreamUrl(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (listOf("username", "user", "uname").any { parsed.queryParameter(it) != null }) return true
        if (listOf("password", "pass", "pwd").any { parsed.queryParameter(it) != null }) return true
        val segments = parsed.pathSegments
        val prefix = segments.firstOrNull()?.lowercase(Locale.US)
        return segments.size >= 4 && prefix in setOf("live", "movie", "series", "timeshift")
    }

    private fun sanitizeExt(ext: String): String {
        val cleaned = ext.trim().trimStart('.').lowercase(Locale.US)
        return cleaned.ifBlank { "mp4" }
    }

    private fun firstNonBlank(obj: com.google.gson.JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            if (!obj.has(key) || obj.get(key).isJsonNull) return@forEach
            val value = obj.get(key).asString.trim()
            if (value.isNotBlank()) return value
        }
        return null
    }
}
