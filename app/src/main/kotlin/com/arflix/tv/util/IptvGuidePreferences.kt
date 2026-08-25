package com.arflix.tv.util

import androidx.datastore.preferences.core.booleanPreferencesKey

/** Device-local preference for the optional EPG VOD action lookup. */
val IPTV_EPG_VOD_ACTIONS_ENABLED_KEY = booleanPreferencesKey("iptv_epg_vod_actions_enabled")
