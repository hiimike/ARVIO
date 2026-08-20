# VOD Source Search & Selection Performance Plan

Status: **implemented in this branch** (commit every phase separately — see §5 rebase guide).

Goal: finding and playing a VOD source must behave like UHF/TiviMate:
source selection starts playback **instantly** (no "Preparing stream" spinner on the OK-press),
and the source search itself never blocks on a catalog download once any cache exists on disk.

Scope: the VOD path only (movies/episodes resolved from Xtream playlists + addons).
Live TV performance is covered separately by `docs/IPTV_TV_PERFORMANCE_PLAN.md` — the two
plans use different marker prefixes (`IPTV-PERF F<n>` vs `VOD-PERF V<n>`) and must both
survive rebases.

## 1. Root causes (evidence)

| # | Symptom | Root cause | Evidence (pre-fix) |
|---|---------|-----------|--------------------|
| S1 | Selecting a source shows "Preparing stream" for up to 8s | `selectStream` **always** blocked on `resolveStreamForPlayback` (8s ceiling) — redirect-chain GETs and HubCloud scraping run before playback starts | `PlayerViewModel.selectStream` → `StreamRepository.resolveStreamInternal`; `STREAM_RESOLUTION_TIMEOUT_MS = 8_000L`, `STREAM_REDIRECT_RESOLUTION_TIMEOUT_MS = 8_000L` |
| S2 | Debrid-cached and HubCloud sources are never pre-resolved | `canPrewarmWithoutSideEffects` treats every redirect-host / hub host as side-effect-prone, so the top-stream prewarm skips exactly the slowest sources | `StreamRepository.isSideEffectPronePrewarmSource` |
| S3 | First source search after app start takes seconds | VOD catalog cold path downloads the full `get_vod_streams` JSON (often 10–30MB) + Gson parse + index build **on the search path**; channels were warmed at startup, VOD catalogs were not | `IptvRepository.loadXtreamVodStreams`; `MainActivity` warmup only called `warmupFromCacheOnly()` |
| S4 | Every search after the 6h TTL stalls on a download | Disk cache was only honored while fresh; a stale cache fell through to a blocking network download before returning anything | `loadXtreamVodStreams` / `loadXtreamSeriesList` step 2 |

## 2. Fixes (already implemented)

### Phase V5 — Instant source selection (the primary fix)
- **V5.1** `StreamRepository`: split `resolveStreamInternal` into a network-free local phase and a network phase.
  - `resolveStreamLocal(stream)` (public): scheme normalization, `splitUrlAndHeaders`, gated-host
    Referer/Origin merge, embedded-link unwrap — pure string work. Returns `null` for magnet/P2P,
    non-HTTP, and HubCloud **page** URLs (those need the network chain).
  - `resolveStreamNetworkPhase(local, original)` (private): redirect resolution + post-redirect unwrap.
    Runs only inside `resolveStreamInternal` now (same behavior as before for all existing callers:
    `resolveStreamForPlayback`, prewarm, `isHttpStreamReachable`).
  - `cachedResolvedStreamForPlayback(stream)` (public): cached-only accessor of the resolve cache.
- **V5.2** Prewarm gate relaxation in `isSideEffectPronePrewarmSource`:
  - HubCloud/HubDrive **page** URLs are prewarm-safe (chain resolution is HTML scraping only — no
    download starts), so they pre-resolve while the user browses the source list.
  - `behaviorHints.cached == true` streams (debrid-cached) resolve without kicking off a fresh
    download, so they pre-resolve too. Everything else keeps the old conservative gate.
- **V5.3** `PlayerViewModel.selectStream` — instant selection order:
  1. fresh resolve-cache hit → immediate (this is the prewarmed path);
  2. HubCloud page URL → resolve-first with the "Preparing stream" spinner (HTML pages can't play);
  3. any other http(s) source → `resolveStreamLocal` + **playback starts immediately** with the
     locally-resolved URL (ExoPlayer follows redirects itself), and a background
     `streamUpgradeJob` runs the full network resolution. The upgrade swaps the media item **only**
     when (a) it yields a different URL, (b) the selection nonce is unchanged, and (c) playback has
     not started for that nonce (`lastStartedPlaybackNonce`, recorded in `onPlaybackStarted`).
     Otherwise the resolved URL just lands in the cache for retries/failover.
  - Known tradeoff (accepted): autoplay may start on an IPTV/debrid source before the best addon
    source arrives; the picker still lists everything and switching works.

### Phase V1 — Stale-while-revalidate catalogs
- **V1** `loadXtreamVodStreams` / `loadXtreamSeriesList`: a stale-but-present disk cache is now
  loaded into memory and returned **immediately**; a single-flight background refresh
  (`refreshXtreamVodStreamsInBackground` / `refreshXtreamSeriesInBackground` on `iptvCacheScope`,
  guarded by `AtomicBoolean`) downloads the fresh catalog and atomically swaps memory + disk.
  Searches never wait on a catalog download again once any cache exists. First-ever install still
  downloads synchronously (nothing to serve stale).

### Phase V2 — Startup pre-warm
- **V2** `IptvRepository.warmupVodCatalogsFromCacheOnly()`: loads VOD streams + series list from
  disk (never network) and pre-builds both search indexes (`cachedVodIdIndex` via
  `getXtreamVodStreams(allowNetwork=false)` and the title-token index via `ensureVodCatalogIndex`).
  Called after `warmupFromCacheOnly()` at app start (`MainActivity`) and on profile switch
  (`ProfileViewModel`).

## 3. Files changed (this work)

| File | Fixes |
|------|-------|
| `app/.../tv/data/repository/StreamRepository.kt` | V5.1, V5.2 |
| `app/.../tv/ui/screens/player/PlayerViewModel.kt` | V5.3 |
| `app/.../tv/data/repository/IptvRepository.kt` | V1, V2 |
| `app/.../tv/MainActivity.kt` | V2 call site |
| `app/.../tv/ui/screens/profile/ProfileViewModel.kt` | V2 call site |

## 4. Verification checklist

1. `./gradlew :app:compilePlayDebugKotlin :app:testPlayDebugUnitTest :app:detekt` pass.
2. **Selection latency**: pick any direct-URL/IPTV VOD source — playback must start with **no**
   "Preparing stream" spinner (`stream_selected` breadcrumb `resolve=` bucket should be the lowest).
   Logcat: `IPTV-PERF` is unrelated; look for `stream_selection_upgraded` breadcrumbs when an
   upgrade happens.
3. **Prewarm**: open the source list for a title with debrid-cached (badge "cached") or HubCloud
   sources, wait ~2s, then select one — must start instantly (cache hit path).
4. **SWR**: with a VOD disk cache older than 6h, open source search — results must appear
   immediately; logcat `[VOD-PERF] V1 background VOD refresh got N items` lands later.
5. **Startup warm**: cold start → open a movie's sources within the first minute — no catalog
   download on the search path (logcat `[VOD-PERF] V2 warmup done` at boot).
6. Not regressed: HubCloud page sources still resolve (spinner path), magnet/P2P sources still show
   the TorrServer message, retries/failover still work after playback errors, quality filters,
   subtitles, debrid non-cached sources still NOT prewarmed (side effects).

---

## 5. Rebase guide (delegate this file to an agent)

This repo has many maintainers; `PlayerViewModel.kt`, `StreamRepository.kt` and
`IptvRepository.kt` are heavily edited upstream, so rebases WILL conflict. Hand the agent this
whole file (§1–§3 give the *why*, §5 the *how*) plus the branch name. When in doubt, the marker
comments in the code (§5.2 ledger) are the source of truth, not memory.

### 5.1 Commit structure (why it matters)
The work is grouped into these commits (V1+V2 share one commit because they both live in
`IptvRepository.kt` plus two tiny call sites; the `VOD-PERF V<n>` markers — not commit
boundaries — are what locate each hunk during a rebase):
```
perf(vod): V5 instant source selection and prewarm relaxation
perf(vod): V1 stale-while-revalidate and V2 pre-warm of VOD catalogs
docs: VOD source search plan and rebase guide
```
Every changed block starts with a marker comment `// VOD-PERF V<n>` (KDoc uses `* VOD-PERF V<n>`).
During a conflict, locate our side of the hunk by its marker — if a resolved file lost a marker
from the §5.2 ledger, the merge dropped behavior. Re-apply it from the pre-rebase commit
(`git show <phase-commit> -- <file>`).

### 5.2 Marker ledger (expected after ANY rebase/merge — verify mechanically)
Run:
```bash
grep -rn "VOD-PERF V" app/src/main/kotlin/com/arflix/tv | grep -E "// VOD-PERF|\* VOD-PERF"
```
Expected inventory (counts may grow if we add fixes, but must never shrink):

| File (under `app/src/main/kotlin/com/arflix/tv/`) | Markers present |
|---|---|
| `MainActivity.kt` | V2 (×1: warmup call after `warmupFromCacheOnly`) |
| `ui/screens/profile/ProfileViewModel.kt` | V2 (×1: warmup call in profile-switch launch) |
| `data/repository/IptvRepository.kt` | V1 (×6: SWR comment + background-refresh fn in `loadXtreamVodStreams`, same pair in `loadXtreamSeriesList`, two `AtomicBoolean` guards), V2 (×1: `warmupVodCatalogsFromCacheOnly`) |
| `data/repository/StreamRepository.kt` | V5.1 (×5: `normalizeStreamUrlScheme`, `resolveStreamLocal`, `cachedResolvedStreamForPlayback`, `resolveStreamNetworkPhase`, call site in `resolveStreamInternal`), V5.2 (×2: hubcloud early-return + cached early-return in `isSideEffectPronePrewarmSource`) |
| `ui/screens/player/PlayerViewModel.kt` | V5.3 (×5: nonce freeze in `onPlaybackStarted`, instant-selection block in `selectStream`, background-upgrade block, `streamUpgradeJob` field, `lastStartedPlaybackNonce` field) |

Also expected (log breadcrumbs, not markers): 3 `[VOD-PERF]` strings in `IptvRepository.kt`
(V2 warmup done, V1 VOD refresh, V1 series refresh).

### 5.3 Conflict-prone files (ranked) and resolution rules

**1. `ui/screens/player/PlayerViewModel.kt` (most-edited upstream)**
- `selectStream` is the hot zone. Our structure must survive:
  1. cached resolve → instant; 2. hubcloud page → spinner + resolve-first; 3. everything else →
     `resolveStreamLocal` + immediate playback + `needsBackgroundUpgrade = true`.
- Rules:
  - Never restore an unconditional `resolveStreamForPlayback(stream)` call before playback for
    non-hubcloud sources — that is the exact 8s stall V5.3 removed.
  - Keep `streamUpgradeJob?.cancel()` at the top of `selectStream` and the three upgrade guards:
    nonce unchanged, `fullUrl != launchedUrl`, `lastStartedPlaybackNonce != launchedNonce`.
  - Keep `lastStartedPlaybackNonce = currentState.streamSelectionNonce` as the first statement in
    `onPlaybackStarted`.
  - If upstream rewrites `selectStream` around our block, merge both: keep their subtitle/failover
    logic, re-apply our selection order + upgrade job.

**2. `data/repository/StreamRepository.kt`**
- Keep the `resolveStreamLocal` / `resolveStreamNetworkPhase` split; `resolveStreamInternal` must
  stay `local phase → network phase` for non-hubcloud URLs and the hubcloud chain must stay
  resolve-first (page URLs are HTML, unplayable as-is).
- V5.2: in `isSideEffectPronePrewarmSource`, the two early `return false` lines (hubcloud page,
  `cached == true`) must stay **before** the ephemeral/redirect/host-marker checks.
- If upstream adds new side-effect hosts to the marker lists, keep them — our early returns only
  exempt hubcloud pages and debrid-cached streams.

**3. `data/repository/IptvRepository.kt` (very large)**
- V1: both `loadXtreamVodStreams` and `loadXtreamSeriesList` must return a stale disk cache
  immediately and schedule the single-flight background refresh; the "network returned empty → use
  stale" fallback is now implicit (stale is served up-front) — do not re-add a blocking download
  before returning when a disk cache exists.
- V2: `warmupVodCatalogsFromCacheOnly()` must stay cache-only (`allowNetwork = false`) — it runs at
  app start and must never hit the provider.
- If upstream changes `requestJson`/`readDiskCache` signatures, adapt our background refresh
  functions to match, keeping the `AtomicBoolean` single-flight guards.

**4. `MainActivity.kt` / `ProfileViewModel.kt`** — trivial call sites; keep the VOD warmup right
after the channel warmup, inside the same IO launcher.

### 5.4 Agent runbook for a rebase
1. `git fetch origin && git rebase origin/main` (or the team's integration branch).
   Expect conflicts mostly on the V5.3 commit (`PlayerViewModel.kt`) and V1/V2 (`IptvRepository.kt`).
2. For each conflicting commit: `git status`; open each conflicted file and locate our hunks by
   their `// VOD-PERF V…` markers (§5.2). Resolve per §5.3 rules; prefer *merge both behaviors*
   over dropping either side.
3. Marker check — nothing lost:
   ```bash
   grep -rn "VOD-PERF V" app/src/main/kotlin/com/arflix/tv | grep -E "// VOD-PERF|\* VOD-PERF"
   ```
   Compare against the §5.2 ledger file-by-file (counts must not shrink).
4. Behavioral assertions (all must pass):
   ```bash
   # V5.3: no unconditional blocking resolve before playback in selectStream
   grep -n "cachedResolvedStreamForPlayback\|resolveStreamLocal\|streamUpgradeJob\|lastStartedPlaybackNonce" app/src/main/kotlin/com/arflix/tv/ui/screens/player/PlayerViewModel.kt
   # V5.2: hubcloud + cached early returns exist in the prewarm gate
   grep -n "isHubCloudPageUrl(url)) return false\|cached == true) return false" app/src/main/kotlin/com/arflix/tv/data/repository/StreamRepository.kt
   # V5.1: split phases exist
   grep -n "fun resolveStreamLocal\|fun resolveStreamNetworkPhase\|fun cachedResolvedStreamForPlayback\|fun normalizeStreamUrlScheme" app/src/main/kotlin/com/arflix/tv/data/repository/StreamRepository.kt
   # V1: background refresh + single-flight guards exist
   grep -n "refreshXtreamVodStreamsInBackground\|refreshXtreamSeriesInBackground\|vodBackgroundRefreshInFlight\|seriesBackgroundRefreshInFlight" app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt
   # V2: warmup exists and is wired
   grep -n "warmupVodCatalogsFromCacheOnly" app/src/main/kotlin/com/arflix/tv/data/repository/IptvRepository.kt app/src/main/kotlin/com/arflix/tv/MainActivity.kt app/src/main/kotlin/com/arflix/tv/ui/screens/profile/ProfileViewModel.kt
   ```
5. Build + tests (no device needed):
   ```bash
   ./gradlew :app:compilePlayDebugKotlin :app:testPlayDebugUnitTest :app:detekt
   ./gradlew :app:assemblePlayDebug
   ```
6. If upstream already shipped an equivalent fix (e.g. their own instant-selection or SWR catalog),
   **dedupe**: prefer the code that covers more cases, or adopt theirs and drop ours via
   `git rebase --skip` — noting it in the rebase summary. Never silently keep both versions of the
   same mechanism (e.g. two background refresh paths or two selection spinners).
7. Report: conflicted commits → resolution per file → marker ledger diff → build/test status →
   the §5.4-step-4 grep outputs.

### 5.5 What the agent must NOT do during rebase
- `git push -f` without explicit approval; reordering/squashing our commits; resolving conflicts by
  blindly taking "theirs" or "ours".
- Re-add a blocking `resolveStreamForPlayback` call before playback for non-hubcloud sources.
- Remove the upgrade guards (nonce/URL/playback-started) — dropping them restarts playing streams.
- Make `warmupVodCatalogsFromCacheOnly` touch the network.
- Remove the `AtomicBoolean` single-flight guards (refresh stampede on every search).
- Move the V5.2 early returns below the ephemeral/host-marker checks (they would never fire).
- Delete `[VOD-PERF]` log lines (field instrumentation).

## 6. Out of scope (future, deliberate)

- **Multi-list VOD search**: `findMovieVodSources`/`findEpisodeVodSources` still search only the
  first Xtream credential (`config.epgUrl ?: config.m3uUrl`). Searching all active playlists in
  parallel is a follow-up (touches `IptvRepository` hot zone — keep it a separate phase).
- **Episode resolver persistence**: the series resolver catalog still persists as a large string in
  SharedPreferences (`iptv_series_resolver_cache_v1`); moving it to the disk-file pattern is a
  follow-up.
- **Offset-windowed search-pick** (shared with the Live TV plan §2 known limitation).
