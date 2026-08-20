# IPTV Live TV Performance Plan — 54k-channel playlists

Status: **implemented in this branch** (commit every phase separately — see §9 rebase guide).

Goal: on a ~54k-channel playlist the Live TV page must behave like UHF/TiviMate:
dpad focus-scroll ≥ 60fps, channel tune-to-first-frame < 2s, category switch < 500ms,
search keystroke < 150ms, zero ANRs / blocking-GC stalls.

## 0. Architecture recap (what already exists — do not regress)

- `IptvChannelStore` (SQLite, streamed cursor I/O) is the only on-disk channel store
  for large playlists. Never re-introduce gzipped-JSON/Gson serialization of 50k channels.
- `paged*` functions in `IptvRepository.kt:2281-2343` serve **windowed** channel reads
  keyed by `currentEpgIndexKey`.
- "Large list" = `> 10_000` channels (`TvViewModel.isLargeIptvList`). All large-list
  branches (reduced EPG prefetch, capped `nowNext`, small startup window) are load-bearing.
- EPG guide data is capped in memory via `capLargeListGuideSnapshot` (priority 360 + 180 keep).
- Buffers/config that must stay: 384MB heap ceiling awareness, `setPrioritizeTimeOverSizeThresholds(false)`.

## 1. Root causes (evidence)

| # | Symptom | Root cause | Evidence |
|---|---------|-----------|----------|
| R1 | Every zap waits 5–10s before playback starts | Synchronous HTTP probe (HEAD→GET, 3s connect / 4s read / 5s call timeouts) runs **before** `prepareStream` | `LiveTvScreen.kt:2101-2133` → `TvViewModel.resolvePlayableStreamUrl` → `IptvPlaybackUrlResolver.kt:53-60`; timeouts `TvViewModel.kt:122-129` |
| R2 | Focus navigation stutters | Channel-by-id lookup does a **full 54k-row table scan on the Main thread** (no index on `id`; PK is `(source_key, ord)`) | `IptvChannelStore.kt:276-290`; called from `TvViewModel.lookupChannelById:744` inside `refreshCurrentChannelEpg`/`refreshCatchupHistoryForChannel` (both launch on Main) |
| R3 | Scrolling down a big category gets slower and slower | Scroll grows `pagedLoadedLimit`, then **re-reads the whole window from offset 0 and re-enriches everything** (O(n²)) | `LiveTvScreen.kt:1092-1100` + window rebuild `LiveTvScreen.kt:555-678` |
| R4 | Whole screen recomposes every few seconds | Every EPG merge emits full `TvUiState`; the 30s clock tick lives at screen root; UI data classes lack Compose stability annotations | `TvViewModel.setUiState:1993`; `LiveTvScreen.kt:428-433` (root `produceState`) |
| R5 | Category miss scans whole store | `scanCategoryWindow` fallback walks all 54k rows in 1k chunks | `LiveTvScreen.kt:574-599`, `:860-898` |
| R6 | Zap uses linear search over loaded list | `zap(delta)` does `all.indexOfFirst` | `LiveTvScreen.kt:1550-1566` |
| R7 | Tune re-creates media pipeline | `prepareStream` calls `stop()` + `clearMediaItems()` every tune; live `targetOffsetMs=8000` | `LiveTvScreen.kt:1922-1960, 1936` |

## 2. Fixes (already implemented)

### Phase 1 — Zap latency
- **F1.1** Probe client timeouts: `TvViewModel.kt` resolver client → connect 1.5s / read 2s / write 1.5s / call 2.5s.
- **F1.2** Tune without waiting: `LiveTvScreen.kt` stream effect now (a) builds the raw URL target synchronously, `prepareStream(...)` immediately, then (b) runs the probe in a background `coroutineScope.launch` and **corrects** the media item only if the resolved URL/MIME differs and the channel hasn't changed.
- **F1.3** Focus pre-resolve: `TvViewModel.prefetchPlaybackTarget(channelId)` background-probes the focused channel's URL; the 450ms settled commit (`commitFocusedChannel`) calls it, so OK-press afterwards hits the resolver cache.
- **F1.4** Retry path keeps synchronous resolve (`forceRefresh=true`) — acceptable there.

### Phase 2 — DB
- **F2.1** `IptvChannelStore` v4 schema: `idx_channels_id ON channels(source_key, id)` + `group_norm` column (`TRIM(group_title)` stored) + `idx_channels_group_norm ON channels(source_key, group_norm)`; `DATABASE_VERSION=4` (upgrade rebuilds, as before).
- **F2.2** `windowForPlaylistGroup`/`countForPlaylistGroup`/`indexOfId` use `group_norm` — no more `TRIM()` full scans.
- **F2.3** `TvViewModel.lookupChannelById` is `suspend` and wraps the paged-DB branch in `withContext(Dispatchers.IO)`; call sites updated.

### Phase 3 — True incremental paging
- **F3.1** Append-only window: `pagedRawWindow` accumulator + `appendPagedStartupChannelState(existing, tailRaw, windowOffset, …)` in `LiveCategory.kt`. New pages append without re-reading/re-enriching previous rows. Reset only on provider/category change.
- **F3.2** `scanCategoryWindow` fallbacks capped at 5,000 scanned rows with a logged warning.
- **F3.3** `zap(delta)` uses `allDisplayChannelIndexById` map instead of `indexOfFirst`.
- **F3.4** Channel numbers derive from absolute ord (`windowOffset + index + 1`) — unchanged semantics, now stable under append-only paging.

### Phase 4 — Recomposition budget
- **F4.1** `@Immutable` on `EnrichedChannel`, `LiveCategoryIndex`, `LiveCategory`, `LiveSection`, `LiveCategoryTree`, `EnrichedChannels` (models in `IptvModels.kt` already immutable).
- **F4.2** Clock moved out of screen root: `EpgGrid` and `MiniPlayerRow` produce their own 30s tick; root `guideClockMillis` retained **only** as a slow key for the ±48h indexed-guide window refresh (effect key, no tree invalidation).
- **F4.3** Query-only state changes reuse `channelLookup`/`groups` (no 54k `associateBy` per keystroke); `TvUiState.groups/channelsByGroup/channelLookup` already reuse via `canReusePreparedContent`.

### Phase 5 — Player
- **F5.1** `prepareStream` drops `stop()`+`clearMediaItems()`: `setMediaItem` transitions in place.
- **F5.2** Live `targetOffsetMs` 8,000 → 3,000.
- **F5.3** `IptvPlaybackUrlResolver` persists its probe cache (url → resolvedUrl/isHls/ts) to `arvio_iptv_probe_cache` prefs; loaded at construction, TTL 5min, LRU 256.

### Phase 6 — Measurement
- **F6.1** `IptvPerfTracer` (logcat filter `IPTV-PERF`) around: tune resolve+prepare, focus commit, window append, search, indexed guide window.

## 3. Files changed (this work)

| File | Fixes |
|------|-------|
| `app/.../tv/data/repository/IptvChannelStore.kt` | F2.1, F2.2 |
| `app/.../tv/data/repository/IptvPlaybackUrlResolver.kt` | F5.3 |
| `app/.../tv/data/repository/IptvRepository.kt` | (paged helpers unchanged; IO wrapping done in callers) |
| `app/.../tv/ui/screens/tv/TvViewModel.kt` | F1.1, F1.3, F2.3, F4.3 |
| `app/.../tv/ui/screens/tv/live/LiveTvScreen.kt` | F1.2, F3.1–F3.3, F4.2, F5.1, F5.2, F6.1 |
| `app/.../tv/ui/screens/tv/live/LiveCategory.kt` | F3.1 helper, F4.1 |
| `app/.../tv/ui/screens/tv/live/EpgGrid.kt` | F4.2 |
| `app/.../tv/ui/screens/tv/live/MiniPlayer.kt` | F4.2 |
| `app/.../tv/util/IptvPerfTracer.kt` (new) | F6.1 |

## 4. Verification checklist

1. `./gradlew :app:assemblePlayDebug` (or the project's configured variant) compiles.
2. Logcat `IPTV-PERF` breadcrumbs: tune <2s (cold) / <500ms (cached/pre-resolved), window append <150ms per 192-row page, focus commit <50ms.
3. 20-channel zap loop: no `stop/clear` flashes, no `playbackDiagnostic` stall between channels.
4. Scroll 10k rows in "All Channels": no repeated window rebuilds (only `append` logs), memory flat.
5. Category list of a 54k provider opens <500ms; fav/recent/search stay correct.
6. Not regressed: favorites, hidden groups, adult filter, catchup playback, Stalker, Xtream EPG, cloud sync.

## 5. Out of scope (future, deliberate)

- Splitting EPG guide into a separate StateFlow (F4.3b) — bigger refactor; low residual cost after `@Immutable` + clock move.
- Server-side "EPG completeness" service for 10k+ guide coverage (already noted in code).

---

## 6. Rebase guide (delegate this file to an agent)

The performance work must survive branch rebases/merges in this collaborative repo. Give the agent
this section verbatim plus the branch name.

### 6.1 Commit structure (why it matters)
Each phase is its own commit, prefixed:
```
perf(iptv): F1 non-blocking probe-based tuning
perf(iptv): F2 channel-store indexes and IO lookups
perf(iptv): F3 append-only paged windows
perf(iptv): F4 immutable models and scoped guide clock
perf(iptv): F5 seamless tune transition and probe cache
perf(iptv): F6 perf tracer
docs: IPTV TV performance plan and rebase guide
```
Every changed block starts with a marker comment `// IPTV-PERF F<n>` (or `F<n.2` for sub-fixes).
After rebase, grep for the marker — any hunk that lost its marker was mis-merged:
```bash
grep -rn "IPTV-PERF" app/src/main/kotlin/com/arflix/tv
```
Each file should still report the markers listed in §3.

### 6.2 Conflict-prone files (ranked) and resolution rules

**1. `LiveTvScreen.kt` (3,159 lines — most-edited UI file upstream)**
- Our windowing state: `pagedRawWindow`, `pagedScopeKeyState`, `appendPagedStartupChannelState` call,
  `pagedLoadedLimit` semantics (grow-only, append behind it).
- Rule: if upstream touched window building too, keep **both**: upstream's category/provider filter
  logic plus our append-only flow. The append path must always feed `buildPagedStartupChannelState`
  or `appendPagedStartupChannelState` on a background dispatcher and publish via `enrichedState.value`.
- Keep `> 10_000` and `variantCollapseLimit` guard branches; never let the "all" list materialize fully.
- Our tune effect: immediate `prepareStream(guessed)` + correction job. If upstream changed `prepareStream`
  or retry logic, merge carefully: the synchronous `resolvePlayableStreamUrl` may only remain on the
  **error-retry path**; the hot path must not await the probe.
- Clock: `EpgGrid`/`MiniPlayerRow` must keep their internal `produceState` clocks; the root may keep at most
  one slow tick used only inside `LaunchedEffect` keys.

**2. `TvViewModel.kt`**
- Resolver client timeouts (connect 1.5s/read 2s/call 2.5s) — do not restore 3–5s values.
- `prefetchPlaybackTarget` + `lookupChannelById` as suspend wrapped in `Dispatchers.IO` — if upstream added
  new callers of `lookupChannelById`, convert those callers to suspend context or use `runBlocking`-free launch.
- Markers: `// IPTV-PERF F1.1`, `F1.3`, `F2.3`, `F4.3`.

**3. `IptvChannelStore.kt`**
- Schema conflict rule: on `DATABASE_VERSION` conflict take the **higher** version and merge `onCreate`
  columns/indexes from both sides (indexes are additive; columns are additive). `onUpgrade` remains drop+recreate.
- `group_norm` must always be written on insert (`bindString(22, channel.group.trim())`) and queried without `TRIM()`.
- Markers: `// IPTV-PERF F2.1`, `F2.2`.

**4. `IptvRepository.kt` (8,682 lines)**
- We avoided touching its core: only IO-dispatcher wrapping happened in `TvViewModel`. If upstream changed
  `paged*` helpers or `loadSnapshot`, prefer upstream's version and re-apply our guarantees:
  - no DB query on Main (grep for `paged` calls outside `withContext(Dispatchers.IO)`);
  - `pagedChannelStoreCount()` caching stays (ANR guard);
  - large-list EPG gating intact (`> LargeIptvListChannelCount` branches).

**5. `LiveCategory.kt`, `EpgGrid.kt`, `MiniPlayer.kt`, `IptvPlaybackUrlResolver.kt`, `IptvModels.kt`**
- Low conflict. Rules: keep `@Immutable` annotations; keep `appendPagedStartupChannelState`; keep internal
  clocks; keep persistent probe cache (prefs `arvio_iptv_probe_cache`).

### 6.3 Agent runbook for a rebase
1. `git fetch origin && git rebase origin/main` (or the team's integration branch).
2. For each conflicting commit, `git status`/`git diff --check`; open the conflict file with markers from §6.2.
3. Resolve per the per-file rules above; prefer *merge both behaviors* over dropping either side; when in doubt,
   keep the larger-list-guard variant and log a `[IPTV-PERF]` breadcrumb.
4. Verify no marker lost: `grep -rn "IPTV-PERF" app/src/main/kotlin/com/arflix/tv` and compare with §3 list.
5. Verify functionally-after-rebase (no device needed for most):
   - `./gradlew :app:assemblePlayDebug` builds;
   - grep assertions: `withContext(Dispatchers.IO)` around `pagedChannelsByIds`; `< 10_000` guards present;
     no `TRIM(group_title)` in `IptvChannelStore` window queries; `targetOffsetMs(3_000)`.
6. If upstream already shipped an equivalent fix (e.g., their own `id` index), **dedupe**: prefer the code that
   covers more cases (index on `(source_key, id)` + `group_norm` path) and drop ours via `git rebase --skip`,
   noting it in the rebase summary message.
7. Report: list of conflicted commits → resolution chosen → final build status → marker grep output.

### 6.4 What the agent must NOT do during rebase
- `git push -f` without explicit approval; reordering/squashing our commits; resolving conflicts by blindly
  taking "theirs"; removing `System.err.println("[IPTV-PERF …")` lines (they are the field instrumentation);
  regenerating the DB (`replaceAll`) on Main; re-introducing sync probe on the zap hot path.