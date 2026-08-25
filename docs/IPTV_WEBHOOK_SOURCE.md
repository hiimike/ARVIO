# IPTV Webhook Playlist Source

Status: **implemented** (see §5 rebase guide).

Goal: allow the app to obtain Xtream credentials (username/password + optional host) from a single webhook endpoint using basic auth, then create or replace the first IPTV playlist entry with a properly formatted Xtream M3U + EPG pair. The feature must be opt-in via secrets and must survive heavy upstream editing of `IptvRepository.kt` and the Settings screens.

## 1. Contract (what the feature actually does)

- Fixed endpoint: `https://hooks.932426.xyz/webhook/db2b991a-1dd2-46f2-9b7d-a167183fdb44`
- Authentication: HTTP Basic Auth with `WEBHOOK_USER` / `WEBHOOK_PASSWORD` (from `secrets.properties` → BuildConfig → `Constants`).
- Expected response shape (exact shape is part of the contract):
  ```json
  { "matchedItem": { "username": "...", "password": "...", "url": "http://format.com" } }
  ```
  or the same fields at the root. The code accepts both.

  - `"url"` (preferred) or `"host"` / `"server"` gives the Xtream server base (e.g. `http://format.com` or `http://format.com:8080`).
  - This field is **mandatory** in the webhook response.
  - Internally this becomes the non-nullable `IptvWebhookCredentials.url: String`.
  - The value is used to build `get.php?...` and `xmltv.php?...` URLs.
- Behavior on success:
  - If no playlists exist → create one named "Source" as the only entry. The webhook `url` **must** be present (no derivation, no secrets fallback).
  - If playlists exist → replace **only the first** (index 0) with credentials applied using the webhook `url`.
  - The resulting entry is always a valid Xtream pair:
    - `get.php?username=...&password=...&type=m3u_plus&output=ts`
    - matching `xmltv.php?...`
  - `parseResponse` throws if the url (or host/server) is missing or blank.
- Integration points:
  - Auto-apply on `IptvRepository.loadSnapshot(...)` when secrets are present **and** (force reload or no active playlists).
  - Explicit user action "Load playlist source" in IPTV settings (mobile + TV layouts).
  - All writes go through `savePlaylists(...)` so normalization, group-order retention, cloud sync invalidation, and cache invalidation run.
- Secrets (optional; feature is a no-op when missing):
  - `WEBHOOK_USER`
  - `WEBHOOK_PASSWORD`
  - `IPTV_WEBHOOK_HOST` (fallback host only)

## 2. Files and markers

All IPTV-WEBHOOK behavior is protected by marker comments. During a rebase, locate our side of every hunk by these markers. If a marker from the ledger below is missing after a merge, the behavior was dropped — re-apply it from the pre-rebase commit.

### 2.1 Marker ledger (verify mechanically after every rebase)

```bash
grep -rn "IPTV-WEBHOOK" app/src/main/kotlin/com/arflix/tv
```

Expected inventory (sub-ids may grow with fixes, but must never shrink):

| File | Markers |
|------|---------|
| `data/repository/IptvWebhookPlaylist.kt` | 1 (object + ENDPOINT), 1.1 (parseResponse), 1.2 (applyToPlaylists) |
| `data/repository/IptvRepository.kt` | 2.1 (isIptvWebhookConfigured), 2.2 (applyWebhookPlaylistSource), 2.3 (auto-apply in loadSnapshot), 2.4 (fetch + auth + parse), 2.5 (delegate to savePlaylists) |
| `ui/screens/settings/SettingsViewModel.kt` | 3.1 (importIptvWebhookSource) |
| `ui/screens/settings/SettingsScreen.kt` | 4.1 (onLoadSource wiring) |

Also verify the new unit test exists and passes:
```bash
./gradlew :app:testPlayDebugUnitTest --tests "*IptvWebhookPlaylistTest*"
```

### 2.2 Supporting changes that must survive

- `app/build.gradle.kts`: three `buildConfigField` entries for `WEBHOOK_USER`, `WEBHOOK_PASSWORD`, `IPTV_WEBHOOK_HOST`. They must remain quoted and listed in the secrets plugin `ignoreList`.
- `util/Constants.kt`: `WEBHOOK_USER`, `WEBHOOK_PASSWORD`, `IPTV_WEBHOOK_HOST` accessors (using `usableSecret`).
- `secrets.defaults.properties`: the three new keys (documented as optional).
- Strings: `settings_iptv_load_source`, `settings_iptv_load_source_subtitle`, `settings_iptv_webhook_missing_auth`, `settings_iptv_webhook_applied` (and their help variants).
- `IptvWebhookPlaylistTest.kt` (5 tests covering parse + replace-first + create + host derivation).

## 3. Conflict-prone files (ranked) and resolution rules

**1. `data/repository/IptvRepository.kt`** (highest risk — very large file, many upstream IPTV changes)

- `loadSnapshot` (the auto-apply site) — keep the exact condition:
  ```kotlin
  if (isIptvWebhookConfigured() &&
      (forcePlaylistReload || activePlaylists(initialConfig).isEmpty()))
  ```
  Do not move the call, do not add profile scoping here.
- `applyWebhookPlaylistSource` must:
  - Check `isIptvWebhookConfigured()` first and throw the standard missing-auth string.
  - Perform a plain GET with `Authorization: Basic ...` using `Constants.WEBHOOK_*`.
  - Delegate the final write to `savePlaylists(updated)`.
  - Return the list that came back from `savePlaylists`.
- `isIptvWebhookConfigured()` must remain a simple two-field check on `Constants` only.
- If upstream refactors `activePlaylists` / `observeConfig` / `savePlaylists`, keep the call sites and re-apply the webhook logic around the new shapes.

**2. `data/repository/IptvWebhookPlaylist.kt`** (new file — low edit risk, high "do not delete / do not gut" risk)

- `parseResponse` must accept both `{"matchedItem": {...}}` and flat objects.
- Username/password key fallbacks (username/user/uname, password/pass/pwd) must stay.
- Host/URL resolution: `"url"` is preferred (for the Xtream server base), then `"host"`, `"server"`, then legacy `m3uUrl`/`playlist` variants. This order must survive rebases.
- `applyToPlaylists` must implement "replace first (index 0) or create exactly one 'Source' entry".
- When first == null (creating the first playlist), the webhook-provided `url` **must** be used; the method must throw if no usable base URL is available.
- For existing entries the webhook `url` is authoritative. Derivation from the old entry or `fallbackHost` is only a last-resort safety net (and still prefers the webhook value).
- `buildXtreamM3u` / `buildXtreamEpg` and the URL rewriting helpers are part of the contract.
- The fixed `ENDPOINT` constant must not be moved or made configurable from inside this file.

**3. `ui/screens/settings/SettingsScreen.kt`**

- The IPTV settings section is edited frequently.
- Keep the "Load playlist source" row (both the mobile `MobileSettingsRow` and the TV `SettingsRow`).
- Keep the `onLoadSource` parameter on `IptvSettings(...)` and the three call sites that pass it.
- Keep the TV focus index math that accounts for the extra row (currently `playlists.size + 4` for the source action, `+ 5` for delete).
- Keep the help text wiring in the TV help section.
- If upstream adds more rows or changes the add/stalker/refresh/delete ordering, re-apply the source row in the same relative position (after Refresh, before Delete) and update the index math + action dispatch.

**4. `ui/screens/settings/SettingsViewModel.kt`**

- `importIptvWebhookSource()` must set `isIptvLoading`, call `applyWebhookPlaylistSource`, update `iptvPlaylists`, show the success/error toast using the exact string keys, call `syncLocalStateToCloud`, and then `refreshIptv(..., force = true)`.
- Error path must surface the message from the exception or the standard "missing auth" string.

**5. `app/build.gradle.kts` and `util/Constants.kt`**

- The three webhook `buildConfigField` lines must remain.
- They must be added to the secrets plugin `ignoreList`.
- `Constants` accessors must continue to use `usableSecret(...)`.
- If the secrets plugin or `localSecretValue` / `escapeBuildConfigString` logic changes, keep the webhook fields wired the same way.

## 4. Agent runbook for a rebase

1. `git fetch origin && git rebase origin/main` (or the team's integration branch).
   Expect conflicts primarily on the commit(s) touching `IptvRepository.kt` and `SettingsScreen.kt`.

2. For each conflicting commit:
   - `git status`
   - Open every conflicted file and locate our hunks by the `// IPTV-WEBHOOK ...` markers.
   - Resolve using the rules in §3. Prefer *merge both behaviors* over dropping the webhook side.

3. Marker ledger verification (must pass with no shrinkage):
   ```bash
   grep -rn "IPTV-WEBHOOK" app/src/main/kotlin/com/arflix/tv
   ```
   Compare counts per file against §2.1.

4. Contract + behavioral assertions (run these greps; all must succeed):
   ```bash
   # Endpoint is still the fixed one
   grep -n 'hooks.932426.xyz/webhook/db2b991a-1dd2-46f2-9b7d-a167183fdb44' \
        app/src/main/kotlin/com/arflix/tv/data/repository/IptvWebhookPlaylist.kt

   # parseResponse still handles matchedItem or root
   grep -n 'matchedItem\|firstNonBlank' \
        app/src/main/kotlin/com/arflix/tv/data/repository/IptvWebhookPlaylist.kt

   # applyToPlaylists still does first-or-create
   grep -n 'applyToPlaylists\|firstOrNull\|list_1\|Source' \
        app/src/main/kotlin/com/arflix/tv/data/repository/IptvWebhookPlaylist.kt

   # Repository still has the guarded fetch + savePlaylists delegation
   grep -n 'isIptvWebhookConfigured\|applyWebhookPlaylistSource\|savePlaylists' \
        app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt

   # Auto-apply still lives in loadSnapshot
   grep -n 'isIptvWebhookConfigured.*forcePlaylistReload\|applyWebhookPlaylistSource' \
        app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt

   # Settings action still wired
   grep -n 'importIptvWebhookSource\|onLoadSource\|Load playlist source' \
        app/src/main/kotlin/com/arflix/tv/ui/screens/settings/Settings*.kt

   # Secrets fields still declared and ignored
   grep -n 'WEBHOOK_USER\|WEBHOOK_PASSWORD\|IPTV_WEBHOOK_HOST' \
        app/build.gradle.kts util/Constants.kt
   ```

5. Unit tests:
   ```bash
   ./gradlew :app:testPlayDebugUnitTest --tests "*IptvWebhookPlaylistTest*"
   ```

6. Build (no device required):
   ```bash
   ./gradlew :app:compilePlayDebugKotlin :app:detekt :app:assemblePlayDebug
   ```

7. If upstream ships an equivalent "fetch credentials from somewhere and turn them into a playlist":
   - Prefer the implementation that covers more cases (ours: fixed webhook + basic auth + first-or-create + always-Xtream + auto-on-empty + explicit action + full savePlaylists path).
   - Or adopt upstream and drop ours via `git rebase --skip`, noting the decision in the rebase summary. Never silently keep two mechanisms that do the same job.

8. Final report should include:
   - List of conflicted commits
   - Resolution chosen per file (with marker references)
   - Marker ledger diff (before vs after)
   - All grep outputs from step 4
   - Test + build status

## 5. What the agent must NOT do during rebase

- Delete or empty `IptvWebhookPlaylist.kt`.
- Change the fixed `ENDPOINT` constant without also updating this document and any callers.
- Weaken `parseResponse` (e.g. stop accepting `matchedItem`, drop key variants, or require host).
- Change `applyToPlaylists` to insert in the middle, append, or create more than one entry.
- Bypass `savePlaylists` in `applyWebhookPlaylistSource` (cloud sync, normalization, invalidation would be lost).
- Move the auto-apply call in `loadSnapshot` to a place that runs on every load even when playlists already exist (it must stay gated by "empty or force").
- Remove the "Load playlist source" row or its `onLoadSource` wiring from the mobile or TV IPTV settings UI.
- Remove the three webhook `buildConfigField` entries or take them out of the secrets `ignoreList`.
- Make `isIptvWebhookConfigured()` read from anywhere except `Constants.WEBHOOK_USER` / `Constants.WEBHOOK_PASSWORD`.
- Drop the unit tests or the string resources for the feature.
- `git push -f` without explicit approval.

## 6. Out of scope (future, deliberate)

- Multiple webhook sources or user-editable webhook URLs in the UI.
- Non-Xtream output formats from the webhook response.
- Storing the webhook credentials anywhere except the existing secrets/BuildConfig path.
- Per-profile webhook configuration (the current design is app-wide via secrets).
- Automatic periodic re-fetch (only on forced refresh or when no playlists are configured).
