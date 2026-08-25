# IPTV Webhook Source (lease-at-play)

Status: **implemented** (see §5 rebase guide).

Goal: the webhook is the only Xtream credential source. Catalog (channels, categories, VOD) is shared and persisted in memory + SQLite. **Every live / catchup / VOD play GETs the webhook for a currently free account and uses that link.** Credentials are never written into playlists, cloud sync, channel rows, or VOD source URLs.

## 1. Contract

- Fixed endpoint: `https://hooks.932426.xyz/webhook/db2b991a-1dd2-46f2-9b7d-a167183fdb44`
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

- `app/build.gradle.kts`: `WEBHOOK_USER`, `WEBHOOK_PASSWORD`, `IPTV_WEBHOOK_HOST` BuildConfig + secrets ignoreList.
- `util/Constants.kt`: same three accessors via `usableSecret`.
- `secrets.defaults.properties`: the three keys.

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
grep -n 'hooks.932426.xyz/webhook/db2b991a-1dd2-46f2-9b7d-a167183fdb44' \
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

grep -n 'WEBHOOK_USER\|WEBHOOK_PASSWORD\|IPTV_WEBHOOK_HOST' \
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
- Change the fixed `ENDPOINT`.
- Make `isIptvWebhookConfigured()` read anything except `Constants.WEBHOOK_*`.
- `git push -f` without explicit approval.

## 6. Out of scope

- Manual playlist fallback.
- Device id / release-on-stop (backend picks a free account).
- User-editable webhook URL.
- Removing Stalker.
- Periodic re-lease while the same item is playing.
