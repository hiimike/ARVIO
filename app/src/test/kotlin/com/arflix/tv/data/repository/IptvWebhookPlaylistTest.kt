package com.arflix.tv.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IptvWebhookPlaylistTest {

    @Test
    fun parsesResponseWithMatchedItemFailsWithoutUrl() {
        // url (or host/server) is now mandatory — missing url must throw.
        // This is intentional: you cannot create the first playlist without an explicit server URL.
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
        // The webhook is expected to return "url" as the Xtream server base.
        val json = """{"matchedItem":{"username":"u","password":"p","url":"http://format.com:8080"}}"""
        val creds = IptvWebhookPlaylist.parseResponse(json)
        assertThat(creds.url).isEqualTo("http://format.com:8080")
    }

    @Test
    fun parsesExactWebhookShapeWithUrl() {
        // Exact shape the user specified for the hook response.
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
    fun appliesToExistingPlaylistReplacesFirst() {
        val existing = listOf(
            IptvPlaylistEntry("list_1", "Old", "http://old/get.php?username=old&password=old", "", true, emptyList()),
            IptvPlaylistEntry("list_2", "Two", "http://two", "", true, emptyList()),
        )
        // url is now mandatory in the response
        val creds = IptvWebhookCredentials("newu", "newp", url = "https://srv")
        val updated = IptvWebhookPlaylist.applyToPlaylists(existing, creds)
        assertThat(updated.size).isEqualTo(2)
        assertThat(updated[0].m3uUrl).contains("username=newu")
        assertThat(updated[0].m3uUrl).contains("password=newp")
        assertThat(updated[0].epgUrl).contains("xmltv.php")
        assertThat(updated[1].name).isEqualTo("Two")
    }

    @Test
    fun createsFirstPlaylistWhenNoneExist() {
        // When creating the first playlist, url from the webhook is required (no derivation possible)
        val creds = IptvWebhookCredentials("u", "p", url = "https://srv")
        val updated = IptvWebhookPlaylist.applyToPlaylists(emptyList(), creds)
        assertThat(updated).hasSize(1)
        assertThat(updated[0].name).isEqualTo("Source")
        assertThat(updated[0].m3uUrl).contains("get.php?username=u&password=p")
    }

    @Test
    fun usesWebhookUrlEvenIfExistingEntryHasDifferentHost() {
        // Webhook url takes precedence over anything stored in an old playlist entry
        val existing = listOf(
            IptvPlaylistEntry("list_1", "Main", "https://old.host:8443/get.php?username=a&password=b", "", true, emptyList()),
        )
        val creds = IptvWebhookCredentials("nu", "np", url = "http://format.com")
        val updated = IptvWebhookPlaylist.applyToPlaylists(existing, creds)
        assertThat(updated[0].m3uUrl).contains("format.com")
        assertThat(updated[0].m3uUrl).contains("username=nu")
        assertThat(updated[0].m3uUrl).contains("password=np")
    }
}
