package com.arflix.tv.data.repository

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class IptvWebhookCredentials(
    val username: String,
    val password: String,
    // The Xtream server base URL (e.g. "http://format.com" or "http://format.com:8080").
    // This is **mandatory** in the webhook response (key "url" preferred, also accepts "host"/"server").
    // Required especially when creating the first playlist, since there is nothing to derive from.
    val url: String,
)

internal object IptvWebhookPlaylist {
    // IPTV-WEBHOOK 1: core object + fixed endpoint.
    // The URL is part of the contract; changing it requires updating callers and docs.
    const val ENDPOINT = "https://hooks.932426.xyz/webhook/db2b991a-1dd2-46f2-9b7d-a167183fdb44"

    // IPTV-WEBHOOK 1.1: parseResponse supports both {"matchedItem": {...}} and flat object.
    // "url" (preferred) or "host"/"server" provides the Xtream server base URL (e.g. http://format.com).
    // Username/password lookup order is unchanged. Host/URL priority is part of the contract.
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
        // IPTV-WEBHOOK 1.1b: "url" (preferred) or "host"/"server" provides the Xtream server base.
        // This value is mandatory — we throw if absent because creating the first playlist
        // requires an explicit server URL from the webhook.
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

    // IPTV-WEBHOOK 1.2: applyToPlaylists — the heart of "replace first or create".
    // Replaces index 0 if present, else creates a new "Source" entry as the only item.
    //
    // The `url` field in IptvWebhookCredentials is **mandatory** (non-nullable String, no default).
    // It comes from the webhook (key "url" preferred, also accepts "host"/"server").
    //
    // Creating the first playlist (first == null) **requires** a non-blank `url` from the webhook.
    // There is no derivation or secrets fallback in the create path — the webhook must supply it.
    //
    // When updating an existing playlist we also use the webhook `url` as the source of truth.
    fun applyToPlaylists(
        playlists: List<IptvPlaylistEntry>,
        credentials: IptvWebhookCredentials,
        fallbackHost: String = "",
    ): List<IptvPlaylistEntry> {
        val first = playlists.firstOrNull()
        val webhookUrl = credentials.url.trim()

        if (first == null) {
            // Creating the first playlist: url from the webhook response is strictly required.
            if (webhookUrl.isBlank()) {
                throw IllegalStateException("Webhook response must provide a non-blank url to create the first playlist")
            }
            val m3u = buildXtreamM3u(webhookUrl, credentials.username, credentials.password)
            val epg = buildXtreamEpg(webhookUrl, credentials.username, credentials.password)
            return listOf(
                IptvPlaylistEntry(
                    id = "list_1",
                    name = "Source",
                    m3uUrl = m3u,
                    epgUrl = epg,
                    enabled = true,
                    epgUrls = listOf(epg),
                )
            )
        }

        // Updating existing: prefer the mandatory webhook url.
        // Only as a last-resort safety net we fall back to deriving from the old entry
        // or the secrets fallbackHost (this should rarely be needed).
        val base = webhookUrl.ifBlank {
            first.m3uUrl.let { xtreamBaseFromUrl(it) }.orEmpty()
        }.ifBlank { fallbackHost.trim() }

        if (base.isBlank()) {
            throw IllegalStateException("No server URL available to update playlist (webhook url was blank and no fallback)")
        }

        val m3u = buildXtreamM3u(base, credentials.username, credentials.password)
        val epg = buildXtreamEpg(base, credentials.username, credentials.password)
        val updated = first.copy(
            m3uUrl = m3u,
            epgUrl = epg,
            epgUrls = listOf(epg),
        )
        return listOf(updated) + playlists.drop(1)
    }

    fun applyCredentialsToUrl(
        url: String,
        username: String,
        password: String,
        hostOverride: String = "",
    ): String {
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull()
        if (parsed != null) {
            val hasUser = listOf("username", "user", "uname").any { parsed.queryParameter(it) != null }
            val hasPass = listOf("password", "pass", "pwd").any { parsed.queryParameter(it) != null }
            if (hasUser || hasPass) {
                val builder = parsed.newBuilder()
                listOf("username", "user", "uname").forEach { key ->
                    if (parsed.queryParameter(key) != null) builder.setQueryParameter(key, username)
                }
                listOf("password", "pass", "pwd").forEach { key ->
                    if (parsed.queryParameter(key) != null) builder.setQueryParameter(key, password)
                }
                return builder.build().toString()
            }
            val path = parsed.encodedPath.lowercase()
            val base = hostOverride.ifBlank {
                parsed.toString().substringBefore('?').trimEnd('/')
                    .removeSuffix("/get.php")
                    .removeSuffix("/xmltv.php")
                    .removeSuffix("/player_api.php")
                    .trimEnd('/')
            }
            return if (path.endsWith("/xmltv.php")) {
                buildXtreamEpg(base, username, password)
            } else {
                buildXtreamM3u(base, username, password)
            }
        }
        if (hostOverride.isNotBlank()) {
            return buildXtreamM3u(hostOverride, username, password)
        }
        return trimmed
    }

    fun xtreamBaseFromUrl(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        return parsed.toString().substringBefore('?').trimEnd('/')
            .removeSuffix("/get.php")
            .removeSuffix("/xmltv.php")
            .removeSuffix("/player_api.php")
            .trimEnd('/')
            .takeIf { it.isNotBlank() }
    }

    fun buildXtreamM3u(baseUrl: String, username: String, password: String): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/get.php?username=$username&password=$password&type=m3u_plus&output=ts"
    }

    fun buildXtreamEpg(baseUrl: String, username: String, password: String): String {
        val safeBase = normalizeHost(baseUrl) ?: baseUrl.trim().trimEnd('/')
        return "$safeBase/xmltv.php?username=$username&password=$password"
    }

    private fun normalizeHost(host: String): String? {
        val h = host.trim().trimEnd('/')
        if (h.isBlank()) return null
        return when {
            h.startsWith("http://", ignoreCase = true) || h.startsWith("https://", ignoreCase = true) -> h
            else -> "http://$h"
        }
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
