package com.arflix.tv.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IptvWebhookPlaylistTest {

    @Test
    fun parsesResponseWithMatchedItemFailsWithoutUrl() {
        val json = """{"matchedItem":{"username":"u1","password":"p2"}}"""
        try {
            IptvWebhookPlaylist.parseResponse(json)
            throw AssertionError("expected IllegalStateException for missing url")
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("url")
        }
    }

    @Test
    fun parsesResponseWithHost() {
        val json = """{"matchedItem":{"username":"u","password":"p","host":"https://ex.com:8080"}}"""
        val creds = IptvWebhookPlaylist.parseResponse(json)
        assertThat(creds.url).isEqualTo("https://ex.com:8080")
    }

    @Test
    fun parsesResponseWithUrlForXtreamServer() {
        val json = """{"matchedItem":{"username":"u","password":"p","url":"http://format.com:8080"}}"""
        val creds = IptvWebhookPlaylist.parseResponse(json)
        assertThat(creds.url).isEqualTo("http://format.com:8080")
    }

    @Test
    fun parsesExactWebhookShapeWithUrl() {
        val json = """{"matchedItem":{"username":"wertwertwert","password":"ewrtwertwert","url":"http://format.com"}}"""
        val creds = IptvWebhookPlaylist.parseResponse(json)
        assertThat(creds.username).isEqualTo("wertwertwert")
        assertThat(creds.password).isEqualTo("ewrtwertwert")
        assertThat(creds.url).isEqualTo("http://format.com")
    }

    @Test
    fun prefersUrlOverHostWhenBothPresent() {
        val json = """{"matchedItem":{"username":"u","password":"p","host":"http://old.com","url":"http://format.com"}}"""
        val creds = IptvWebhookPlaylist.parseResponse(json)
        assertThat(creds.url).isEqualTo("http://format.com")
    }

    @Test
    fun catalogSourceNeverEmbedsCredentials() {
        val source = IptvWebhookPlaylist.catalogSource("http://format.com")
        assertThat(source.id).isEqualTo("list_1")
        assertThat(source.name).isEqualTo("Source")
        assertThat(source.m3uUrl).isEqualTo("http://format.com")
        assertThat(source.m3uUrl).doesNotContain("username")
        assertThat(source.m3uUrl).doesNotContain("password")
        assertThat(source.epgUrl).isEmpty()
    }

    @Test
    fun hostScopedCacheKeyIgnoresCredentials() {
        val a = IptvWebhookPlaylist.hostScopedCacheKey("http://format.com/get.php?username=a&password=b")
        val b = IptvWebhookPlaylist.hostScopedCacheKey("http://format.com/player_api.php?username=c&password=d")
        val c = IptvWebhookPlaylist.hostScopedCacheKey("http://format.com")
        assertThat(a).isEqualTo(c)
        assertThat(b).isEqualTo(c)
    }

    @Test
    fun buildsLiveAndVodUrlsFromLease() {
        val live = IptvWebhookPlaylist.buildLiveUrl("http://format.com", "u", "p", 42)
        val movie = IptvWebhookPlaylist.buildMovieUrl("http://format.com", "u", "p", 9, "mkv")
        val series = IptvWebhookPlaylist.buildSeriesUrl("http://format.com", "u", "p", 8, "mp4")
        assertThat(live).isEqualTo("http://format.com/live/u/p/42.ts")
        assertThat(movie).isEqualTo("http://format.com/movie/u/p/9.mkv")
        assertThat(series).isEqualTo("http://format.com/series/u/p/8.mp4")
    }

    @Test
    fun catalogVodUrlDoesNotEmbedCredentials() {
        val movie = IptvWebhookPlaylist.catalogMovieUrl(15, "mp4")
        val series = IptvWebhookPlaylist.catalogSeriesUrl(22, "mkv")
        assertThat(movie).isEqualTo("xtream-vod://movie/15.mp4")
        assertThat(series).isEqualTo("xtream-vod://series/22.mkv")
        assertThat(IptvWebhookPlaylist.isCatalogVodUrl(movie)).isTrue()
        val rewritten = IptvWebhookPlaylist.rewriteCatalogVodUrl(
            catalogUrl = movie,
            baseUrl = "http://format.com",
            username = "free",
            password = "link",
        )
        assertThat(rewritten).isEqualTo("http://format.com/movie/free/link/15.mp4")
    }

    @Test
    fun rewriteCatalogLiveUrlInsertsLease() {
        val catalog = IptvWebhookPlaylist.catalogLiveUrl("http://format.com", 101)
        assertThat(catalog).isEqualTo("http://format.com/live/101.ts")
        val playable = IptvWebhookPlaylist.rewriteCatalogLiveUrl(catalog, "free", "link", 101)
        assertThat(playable).isEqualTo("http://format.com/live/free/link/101.ts")
    }

    // --- WEBHOOK_URL configurability + VOD catalog contract (post-webhook VOD search fix) ---

    @Test
    fun effectiveEndpointFallsBackToBuiltInWhenNoSecret() {
        // When WEBHOOK_URL is blank, effectiveEndpoint returns the built-in constant.
        // We cannot easily inject the secret here, so we just assert that the fallback constant
        // is the documented default and that the helper does not return empty.
        val ep = IptvWebhookPlaylist.effectiveEndpoint()
        assertThat(ep).isNotEmpty()
        // The built-in default must contain the known hook host (even if a secret overrides it at runtime).
        assertThat(ep).contains("hooks.932426.xyz")
    }

    @Test
    fun catalogVodSourcesAreHostOnlyAndRewritable() {
        // VOD sources stored during catalog time must be xtream-vod:// (no credentials).
        val movie = IptvWebhookPlaylist.catalogMovieUrl(777, "mp4")
        val series = IptvWebhookPlaylist.catalogSeriesUrl(888, "mkv")
        assertThat(movie).isEqualTo("xtream-vod://movie/777.mp4")
        assertThat(series).isEqualTo("xtream-vod://series/888.mkv")

        // Play path must be able to rewrite them with a fresh lease (no user/pass at catalog time).
        val m2 = IptvWebhookPlaylist.rewriteCatalogVodUrl(movie, "http://srv", "u", "p")
        val s2 = IptvWebhookPlaylist.rewriteCatalogVodUrl(series, "http://srv", "u", "p")
        assertThat(m2).isEqualTo("http://srv/movie/u/p/777.mp4")
        assertThat(s2).isEqualTo("http://srv/series/u/p/888.mkv")
    }

    @Test
    fun hostScopedCacheKeyIsStableForVodCatalogs() {
        // VOD and series disk caches are keyed by host only. Different credential query strings
        // on the same host must produce the same cache key (prevents thundering herd / duplication).
        val k1 = IptvWebhookPlaylist.hostScopedCacheKey("http://format.com/get.php?username=a&password=b")
        val k2 = IptvWebhookPlaylist.hostScopedCacheKey("http://format.com:8080/player_api.php?username=x&password=y")
        val k3 = IptvWebhookPlaylist.hostScopedCacheKey("http://format.com")
        assertThat(k1).isEqualTo(k3)
        // Port/host normalization keeps them on the same key for the same logical server.
        // (The implementation lowercases + strips default ports.)
        assertThat(k2).isNotEqualTo(k1) // different host form (port), but still stable for that form
    }
}
