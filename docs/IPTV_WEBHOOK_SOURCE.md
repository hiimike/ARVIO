# IPTV Webhook Source (lease-at-play)

Status: **implemented** (see §5 rebase guide).

> This plan now includes §7 (VOD catalog + search after webhook) and §8 (cross-plan rebase/merge survival guide).
> See also `IPTV_TV_PERFORMANCE_PLAN.md` §7–8 and `VOD_SOURCE_SEARCH_PLAN.md` §7–8.

Goal: the webhook is the only Xtream credential source. Catalog (channels, categories, VOD) is shared and persisted in memory + SQLite. **Every live / catchup / VOD play GETs the webhook for a currently free account and uses that link.** Credentials are never written into playlists, cloud sync, channel rows, or VOD source URLs.

## 1. Contract

- Lease endpoint: build-time configurable via the `WEBHOOK_URL` secret (full URL including path). When `WEBHOOK_URL` is blank, the built-in default is used.
- Authentication: HTTP Basic Auth with `WEBHOOK_USER` / `WEBHOOK_PASSWORD`.
- Expected response:
  ```json
  { "matchedItem": { "username": "...", "password": "...", "url": "http://format.com" } }
  ```
  or the same fields at the root. `"url"` / `"host"` / `"server"` is mandatory.
- Backend already picks a **free** account on every GET. The app does not send device id and does not release on stop.
- Play contract:

  | Action | Webhook GET? |
  |---|---|
  | Browse Live TV / search VOD | No |
  | Play live channel | Yes, always |
  | Start catchup | Yes, always |
  | Select IPTV VOD / series | Yes, always |
  | Switch to another item | Yes again |
  | Same item still playing | No |
  | Refresh catalog | Yes, catalog-only; creds dropped after write |
  | Focus prefetch / scroll | No |

- Catalog identity: synthetic playlist `list_1` / `Source` with **host-only** `m3uUrl` (no user/pass).
- Cache keys (`xtreamCacheKey`, VOD disk hash, source signature) are **host-scoped** in webhook mode.
- Stored live URL: `{base}/live/{streamId}.ts`. Stored VOD URL: `xtream-vod://movie|series/{id}.{ext}`.
- Settings: no add/edit/load playlist. Keep Stalker, categories, sort, Refresh, Clear.

## 2. Files and markers

```bash
grep -rn "IPTV-WEBHOOK" app/src/main/kotlin/com/arflix/tv
```

| File | Markers |
|---|---|
| `data/repository/IptvWebhookPlaylist.kt` | 1 (ENDPOINT), 1.1 (parseResponse), 1.2 (catalog identity + URL builders) |
| `data/repository/IptvRepository.kt` | 2.1 configured check, 2.2 catalog lease field, 2.3 loadSnapshot identity, 2.4 fetchLease, 2.5 persist host-only, 2.6 play rewrite |
| `ui/screens/tv/TvViewModel.kt` | lease in `resolvePlayableStreamUrl`; no lease on prefetch |
| `ui/screens/player/PlayerViewModel.kt` | lease in `selectStream` for `iptv_xtream_vod` |
| `ui/screens/settings/SettingsViewModel.kt` | 3.1 Refresh only |
| `ui/screens/settings/SettingsScreen.kt` | no add-playlist / load-source rows |

```bash
./gradlew :app:testPlayDebugUnitTest --tests "*IptvWebhookPlaylistTest*"
```

### 2.2 Supporting changes

- `app/build.gradle.kts`: `WEBHOOK_USER`, `WEBHOOK_PASSWORD`, `IPTV_WEBHOOK_HOST`, `WEBHOOK_URL` BuildConfig + secrets ignoreList.
- `util/Constants.kt`: same accessors via `usableSecret` (plus `WEBHOOK_URL`).
- `secrets.defaults.properties`: the four keys (USER, PASSWORD, URL, HOST).

## 3. Conflict-prone files

**1. `IptvRepository.kt`**

- `fetchWebhookLease()` is a plain GET + Basic Auth. It must **not** call `savePlaylists` with username/password.
- `persistWebhookCatalogIdentity` writes host-only `IptvWebhookPlaylist.catalogSource(host)`.
- `loadSnapshot` leases only when force-reload or cache/playlists are empty. `webhookCatalogLease` is cleared in `finally`.
- `xtreamCacheKey` / `xtreamDiskCacheHash` must be host-only when webhook is configured.
- Play helpers `resolveWebhookPlaybackUrl` / `leaseWebhookVodSource` always call `fetchWebhookLease()`.

**2. `IptvWebhookPlaylist.kt`**

- Keep `parseResponse` key fallbacks.
- Do **not** restore `applyToPlaylists`.
- Catalog builders must not embed credentials.

**3. `SettingsScreen.kt`**

- No Add playlist / Load playlist source rows.
- Source row opens category management only.
- Keep Stalker, sort, Refresh, Clear. Focus math: stalker=0, sources=1..n, sort=n+1, refresh=n+2, clear=n+3.

**4. `TvViewModel` / `PlayerViewModel`**

- Live/catchup/VOD play always leases.
- Prefetch must skip webhook live.

## 4. Agent runbook for a rebase

1. `git fetch origin && git rebase origin/main`
2. Resolve conflicts by marker; merge both behaviors, never drop lease-at-play.
3. Marker ledger: `grep -rn "IPTV-WEBHOOK" app/src/main/kotlin/com/arflix/tv`
4. Contract greps (all must succeed):

```bash
grep -n 'effectiveEndpoint\|ENDPOINT' \
     app/src/main/kotlin/com/arflix/tv/data/repository/IptvWebhookPlaylist.kt

grep -n 'matchedItem\|catalogSource\|catalogLiveUrl\|xtream-vod' \
     app/src/main/kotlin/com/arflix/tv/data/repository/IptvWebhookPlaylist.kt

# Must NOT reappear as a production importer
! grep -n 'fun applyToPlaylists' \
     app/src/main/kotlin/com/arflix/tv/data/repository/IptvWebhookPlaylist.kt

grep -n 'fetchWebhookLease\|persistWebhookCatalogIdentity\|resolveWebhookPlaybackUrl\|leaseWebhookVodSource' \
     app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt

grep -n 'resolveWebhookPlaybackUrl\|isIptvWebhookConfigured' \
     app/src/main/kotlin/com/arflix/tv/ui/screens/tv/TvViewModel.kt

grep -n 'leaseWebhookVodSource' \
     app/src/main/kotlin/com/arflix/tv/ui/screens/player/PlayerViewModel.kt

# Settings must not offer add-playlist / load-source
! grep -n 'settings_iptv_load_source\|add_playlist' \
     app/src/main/kotlin/com/arflix/tv/ui/screens/settings/SettingsScreen.kt | grep -v 'settings_add_stalker'

grep -n 'WEBHOOK_USER\|WEBHOOK_PASSWORD\|IPTV_WEBHOOK_HOST\|WEBHOOK_URL' \
     app/build.gradle.kts app/src/main/kotlin/com/arflix/tv/util/Constants.kt
```

5. Tests: `./gradlew :app:testPlayDebugUnitTest --tests "*IptvWebhookPlaylistTest*"`
6. Build: `./gradlew :app:compilePlayDebugKotlin :app:detekt :app:assemblePlayDebug`
7. If upstream reintroduces “import webhook into a playlist”, keep lease-at-play and drop the importer.
8. Report: conflicted commits, marker ledger, grep outputs, test/build status.

## 5. What the agent must NOT do during rebase

- Restore `applyToPlaylists` / `savePlaylists` with webhook username/password.
- Bake user/pass into `IptvChannel.streamUrl` or `StreamSource.url` at catalog time.
- Put username/password into `xtreamCacheKey` / VOD disk hash in webhook mode.
- Lease on focus prefetch.
- Reuse the last play lease for the next play.
- Add playlist URL input or “Load playlist source”.
- Hard-code a lease URL inside source (use `WEBHOOK_URL` secret + `IptvWebhookPlaylist.effectiveEndpoint()` instead).
- Make `isIptvWebhookConfigured()` read anything except `Constants.WEBHOOK_*` (USER+PASSWORD remain the auth gate; URL may be provided via WEBHOOK_URL).
- `git push -f` without explicit approval.

## 6. Out of scope

- Manual playlist fallback.
- Device id / release-on-stop (backend picks a free account).
- User-editable webhook URL.
- Removing Stalker.
- Periodic re-lease while the same item is playing.

## 7. VOD catalog + search integration (post-webhook)

The original webhook plan focused on live/catchup play. After switching to lease-at-play + host-only catalogs, VOD source search was broken because the old warmup paths no longer ran and the search path performed a full blocking catalog download.

### 7.1 Catalog-time VOD population (the fix)

- While `webhookCatalogLease` is held (inside `loadSnapshot`), we also fetch `get_vod_streams` + `get_series` using the leased credentials and persist them to the **host-scoped** disk cache.
- Indexes are built immediately so subsequent searches are fast.
- The lease is still dropped in `finally` as before (never kept for play).
- This is the equivalent of "VOD data is loaded in memory when a playlist was updated".

Marker in code:
```kotlin
// IPTV-WEBHOOK + VOD-PERF F0: VOD catalog is populated at catalog refresh time while a lease (if any) is still held.
```

### 7.2 Search must be cache-first

- `StreamRepository.resolveMovieVodSources` / `resolveEpisodeVodSources` now call `find*VodSources(allowNetwork = false)` for IPTV VOD sources.
- A full network catalog fetch from the picker is forbidden once any host-scoped cache exists.
- `sourceSearchActive` must be cleared promptly on a cache miss (no indefinite spinner).

### 7.3 Warmup / prefetch paths

- `warmXtreamVodCachesIfPossible`, `prefetchEpisodeVodResolution`, `prefetchSeriesInfoForShow` must go through `xtreamVodSearchCredentials(config)` (primary + secondaries, webhook-aware) instead of the old single `resolveXtreamCredentials(url)`.

### 7.4 WEBHOOK_URL secret (configurable lease endpoint)

- The lease target is no longer a hardcoded constant.
- `WEBHOOK_URL` (full URL) is provided via secrets at build time.
- `IptvWebhookPlaylist.effectiveEndpoint()` returns the secret value when non-blank, otherwise the built-in default.
- `isIptvWebhookConfigured()` still gates on `WEBHOOK_USER` + `WEBHOOK_PASSWORD`.
- All lease fetches use `IptvWebhookPlaylist.effectiveEndpoint()`.

### Rebase / merge rules (webhook + VOD)

- Keep `fetchWebhookLease()` using `effectiveEndpoint()`.
- Keep the VOD warm block inside `loadSnapshot` (the block that runs while the lease is non-null).
- Keep `allowNetwork = false` on the IPTV VOD resolution paths in StreamRepository.
- Keep the updated `warmXtreamVodCachesIfPossible` and prefetch methods that use `xtreamVodSearchCredentials`.
- Never put username/password into `xtream-vod://` URLs or into `xtreamDiskCacheHash`.
- Never call `player_api.php` for VOD on the source-search hot path when a disk cache exists.
- If upstream changes loadSnapshot or the lease lifetime, preserve the "while lease held → warm VOD+series" guarantee.

### Marker / contract greps (in addition to §4)

```bash
grep -n 'IPTV-WEBHOOK + VOD-PERF F0\|VOD catalog is populated at catalog refresh' \
     app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt

grep -n 'allowNetwork = false' \
     app/src/main/kotlin/com/arflix/tv/data/repository/StreamRepository.kt | grep -i vod

grep -n 'xtreamVodSearchCredentials\|warmXtreamVodCachesIfPossible' \
     app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt

grep -n 'effectiveEndpoint' \
     app/src/main/kotlin/com/arflix/tv/data/repository/IptvWebhookPlaylist.kt
```

### What the agent must NOT do (webhook + VOD additions)

- Drop the catalog-time VOD fetch while the lease is active.
- Re-introduce `allowNetwork = true` on the IPTV VOD supplemental search path.
- Restore single-creds `resolveXtreamCredentials` calls in warmup/prefetch for webhook mode.
- Hard-code the lease URL (must use `effectiveEndpoint()` / `WEBHOOK_URL` secret).
- Allow VOD catalog download to be triggered from the source picker when a host cache exists.

## 8. Cross-plan invariants (for multi-plan rebases)

When rebasing a branch that contains work from IPTV_TV_PERFORMANCE_PLAN + IPTV_WEBHOOK_SOURCE + VOD_SOURCE_SEARCH_PLAN + the TV-UX follow-ups:

- All three marker families must be present: `IPTV-PERF F`, `IPTV-WEBHOOK`, `VOD-PERF V`.
- Newer TV-UX markers (`TV-UX T1`, `TV-UX T2`) must be present.
- The webhook + VOD F0 block and cache-first rules must be present.
- `scrollResetKey` at EpgGrid call sites must remain scope-only (`$provider|$category`).
- Progressive Back to main navbar (TV-UX): `isAtSearchEntry`, `focusCategoryRail` / `focusTopBar` / `focusSearchEntryInSidebar`, the zone + `isAtSearchEntry` branching in the main `BackHandler`, `isFocusActive`/`railWantsFocus` + `focusable` guards in CategorySidebar, and `onSearchEntryFocusChanged` callback must survive.
- `isIptvWebhookConfigured()` must only read `WEBHOOK_USER` + `WEBHOOK_PASSWORD` (URL may come from secret).
- No credentials in `xtream-vod://` sources or cache keys.
- Lease only on actual play, never on focus/scroll/search.
