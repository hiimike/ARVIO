# IPTV Live TV Performance Plan — 54k-channel playlists

Status: **implemented in this branch** (commit every phase separately — see §6 rebase guide).

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

### Phase 7 — Scroll stability (post-F1..F6 user reports: "list keeps growing while I'm stuck; every return re-scrolls from the top")
- **F7.1** `scrollResetKey` is now scope-only (`provider|category`); `EpgGrid` reset effect tracks `appliedScrollScope` and repositions ONLY on a real scope switch (to row 0 of the anchored window) or on fresh composition (restore onto the selected/playing channel). Window growth/slides no longer snap the viewport to the top — the old key contained `filteredChannelsWindowKey` + `normalizedGuideStart`, so every appended page dumped the user at row 0. Removed the racy one-shot `didPositionInitialSelection` positioning effect; explicit focus requests are served by the signal effect.
- **F7.2** `ChannelWindowPrefetchThreshold` 10 → 24: the next window is requested ~24 rows before the edge so appends land ahead of the dpad.
- **F7.3** `requestGuideWindowAfter` starts the data append when the visible window is within a full `GuidePagedLoadStepRows` (192) of the loaded tail (was `GuidePageRows` = 48).
- **F7.4** `pagedPlaylistGroupCounts()` (GROUP BY over the whole store) is fetched once per scope; appends reuse `lastKnownPlaylistGroupCounts`.
- **F7.5** Warm-up append: 1.2s after a new scope paints, bump `pagedLoadedLimit` by one step (large lists only, skipped if the user is already paging) so the first scroll-down never waits on the DB+enrichment chain.
- **F7.6** When an append lands while the viewport is pinned at the loaded tail, the visible window auto-expands into the fresh rows (`expandGuideWindowAfter`) instead of waiting for the next key press.
- Known limitation (deliberate, future): search-pick / deep jump to a channel outside the loaded 0-based accumulator still cannot page from an arbitrary offset — needs offset-windowed accumulator support.

## 3. Files changed (this work)

| File | Fixes |
|------|-------|
| `app/.../tv/data/repository/IptvChannelStore.kt` | F2.1, F2.2 |
| `app/.../tv/data/repository/IptvPlaybackUrlResolver.kt` | F5.3 |
| `app/.../tv/data/repository/IptvRepository.kt` | (paged helpers unchanged; IO wrapping done in callers) |
| `app/.../tv/ui/screens/tv/TvViewModel.kt` | F1.1, F1.3, F2.3, F4.3 |
| `app/.../tv/ui/screens/tv/live/LiveTvScreen.kt` | F1.2, F3.1–F3.3, F4.2, F5.1, F5.2, F6.1, F7.1, F7.3–F7.6 |
| `app/.../tv/ui/screens/tv/live/LiveCategory.kt` | F3.1 helper, F4.1 |
| `app/.../tv/ui/screens/tv/live/EpgGrid.kt` | F4.2, F7.1, F7.2 |
| `app/.../tv/ui/screens/tv/live/MiniPlayer.kt` | F4.2 |
| `app/.../tv/util/IptvPerfTracer.kt` (new) | F6.1 |

## 4. Verification checklist

1. `./gradlew :app:assemblePlayDebug` (or the project's configured variant) compiles.
2. Logcat `IPTV-PERF` breadcrumbs: tune <2s (cold) / <500ms (cached/pre-resolved), window append <150ms per 192-row page, focus commit <50ms.
3. 20-channel zap loop: no `stop/clear` flashes, no `playbackDiagnostic` stall between channels.
4. Scroll 10k rows in "All Channels": no repeated window rebuilds (only `append` logs), memory flat.
5. Category list of a 54k provider opens <500ms; fav/recent/search stay correct.
6. Not regressed: favorites, hidden groups, adult filter, catchup playback, Stalker, Xtream EPG, cloud sync.
7. F7 scroll stability: scroll deep into a big category while pages append — the viewport must NEVER
   jump back to row 0; exit fullscreen / leave to the sidebar and come back — the list lands on the
   playing/selected channel, not the top; holding DOWN at the tail keeps producing rows (no dead end
   longer than ~1s).

## 5. Out of scope (future, deliberate)

- Splitting EPG guide into a separate StateFlow (F4.3b) — bigger refactor; low residual cost after `@Immutable` + clock move.
- Server-side "EPG completeness" service for 10k+ guide coverage (already noted in code).

---

## 6. Rebase guide (delegate this file to an agent)

This repo has many maintainers; `LiveTvScreen.kt` is one of the most-edited files upstream, so
rebases/merges WILL conflict. The performance work must survive them. Hand the agent this whole
file (§1–§3 give the *why*, §6 the *how*) plus the branch name. Everything below is written so an
agent can execute it mechanically: when in doubt, the marker comments in the code (§6.2 ledger)
are the source of truth, not memory.

### 6.1 Commit structure (why it matters)
Each phase is its own commit, prefixed:
```
perf(iptv): F1 non-blocking probe-based tuning
perf(iptv): F2 channel-store indexes and IO lookups
perf(iptv): F3 append-only paged windows
perf(iptv): F4 immutable models and scoped guide clock
perf(iptv): F5 seamless tune transition and probe cache
perf(iptv): F6 perf tracer
perf(iptv): F7 scroll stability and ahead-of-dpad paging
docs: IPTV TV performance plan and rebase guide
```
Every changed block starts with a marker comment `// IPTV-PERF F<n>` (or `F<n.m>` for sub-fixes).
During a conflict, locate our side of the hunk by its marker — if a resolved file lost a marker
from the §6.2 ledger, the merge dropped behavior. Re-apply it from the pre-rebase commit
(`git show <phase-commit> -- <file>`).

### 6.2 Marker ledger (expected after ANY rebase/merge — verify mechanically)
Run:
```bash
grep -rn "IPTV-PERF F" app/src/main/kotlin/com/arflix/tv
```
Expected inventory (sub-fix ids, one line = one marker comment; counts may grow if we add fixes,
but must never shrink):

| File (under `app/src/main/kotlin/com/arflix/tv/`) | Markers present |
|---|---|
| `data/repository/IptvChannelStore.kt` | F2.1 (×4: comment in onCreate, index block, indexOfId, DATABASE_VERSION comment), F2.2 (×3: insert, windowForPlaylistGroup, countForPlaylistGroup) |
| `data/repository/IptvPlaybackUrlResolver.kt` | F5.3 (×4: init, persist call, loadPersistentCache, persistCache) |
| `ui/screens/tv/TvViewModel.kt` | F1.1, F1.3 (×2: job guard, prefetchPlaybackTarget), F2.3 (×2: lookupChannelById, pre-check), F4.3 |
| `ui/screens/tv/live/LiveTvScreen.kt` | F1.2, F1.3, F3.1 (×3: accumulator, snapshot-window-key, tail load), F3.2 (×2), F3.3 (×2), F4.1, F4.2, F5.1, F5.2, F7.1 (×2: both EpgGrid call sites), F7.3, F7.4, F7.5, F7.6 |
| `ui/screens/tv/live/LiveCategory.kt` | F3.1 (×3: appendPagedStartupChannelState, bucketPagedWindow, buildPagedCategoryTree), F4.1 (×5) |
| `ui/screens/tv/live/EpgGrid.kt` | F4.2, F7.1, F7.2 |
| `ui/screens/tv/live/MiniPlayer.kt` | F4.2 |
| `util/IptvPerfTracer.kt` (whole file is F6.1) | F6.1 |

### 6.3 Conflict-prone files (ranked) and resolution rules

**1. `LiveTvScreen.kt` (~3,300 lines — most-edited UI file upstream)**

Windowing state that must survive (grep these names after rebase):
`pagedLoadedLimit`, `lastKnownPagedTotal`, `pagedRawWindowState`, `pagedScopeKey`,
`pagedSnapshotWindowKey`, `lastKnownPlaylistGroupCounts`, `lastFilteredSize`,
and the calls into `buildPagedStartupChannelState` / `appendPagedStartupChannelState`.

- Rule: if upstream touched window building too, keep **both**: upstream's category/provider filter
  logic plus our append-only flow. The append path must always feed `buildPagedStartupChannelState`
  or `appendPagedStartupChannelState` on a background dispatcher and publish via `enrichedState.value`.
- Keep `> 10_000` and `variantCollapseLimit` guard branches; never let the "all" list materialize fully.
- Our tune effect: immediate `prepareStream(guessed)` + background correction job. If upstream changed
  `prepareStream` or retry logic, merge carefully: the synchronous `resolvePlayableStreamUrl` may only
  remain on the **error-retry path**; the hot path must not await the probe.
- Clock: `EpgGrid`/`MiniPlayerRow` must keep their internal `produceState` clocks; the root may keep at
  most one slow tick used only inside `LaunchedEffect` keys.

F7 invariants in this file (each one has a marker comment):
- **Both** `EpgGrid(...)` call sites (touch-rail layout and TV layout) pass the scope-only
  `scrollResetKey = "$selectedProviderId|$selectedCategoryId"`. If upstream adds new state to the
  grid, fine — but never re-add `filteredChannelsWindowKey` or `normalizedGuideStart` to this key:
  that reintroduces the snap-to-row-0-on-every-appended-page bug.
- In the big paging `LaunchedEffect` (keyed on `state.snapshot.channels, …, pagedLoadedLimit`):
  scope identity (`scopeKey`/`snapshotWindowKey`/`storeRewritten`/`isNewScope`) is computed **before**
  the group-counts read, and `pagedPlaylistGroupCounts()` is only called when
  `isNewScope || lastKnownPlaylistGroupCounts.isEmpty()` (F7.4). Do not let a merge move the GROUP BY
  back onto every append.
- `requestGuideWindowAfter` bumps `pagedLoadedLimit` when the visible window end is within
  `GuidePagedLoadStepRows` of the loaded tail (F7.3 — the old gate was `GuidePageRows`).
- The guide-window management effect (keyed on `selectedProviderId, selectedCategoryId,
  filteredChannelsWindowKey`) ends with the F7.6 tail-pinned auto-expand branch and
  `lastFilteredSize = filteredChannels.size`; keep both.
- F7.5 warm-up effect (`LaunchedEffect(pagedScopeKey)` that bumps `pagedLoadedLimit` after 1.2s):
  **compile-order trap** — it reads `selectedCategoryTotalCount`, so it must stay AFTER that `val`
  is declared (mid-composable). If a conflict resolution relocates it above the declaration, the
  build fails with `Unresolved reference 'selectedCategoryTotalCount'`.
- If upstream adds a NEW `EpgGrid` call site, give it the same scope-only `scrollResetKey`.

**2. `TvViewModel.kt`**
- Resolver client timeouts (connect 1.5s/read 2s/call 2.5s) — do not restore 3–5s values.
- `prefetchPlaybackTarget` + `lookupChannelById` as suspend wrapped in `Dispatchers.IO` — if upstream
  added new callers of `lookupChannelById`, convert those callers to suspend context (no `runBlocking`).
- Markers: F1.1, F1.3, F2.3, F4.3.

**3. `IptvChannelStore.kt`**
- Schema conflict rule: on `DATABASE_VERSION` conflict take the **higher** version and merge `onCreate`
  columns/indexes from both sides (indexes are additive; columns are additive). `onUpgrade` remains
  drop+recreate. Our schema is v4: `group_norm` column, `idx_channels_id`, `idx_channels_group_norm`.
- `group_norm` must always be written on insert (`bindString(7, channel.group.trim())` — bind position
  follows the current column order; keep it pre-trimmed) and queried without `TRIM()`.
- Markers: F2.1, F2.2.

**4. `IptvRepository.kt` (very large)**
- We avoided touching its core: only IO-dispatcher wrapping happened in callers. If upstream changed
  `paged*` helpers or `loadSnapshot`, prefer upstream's version and re-apply our guarantees:
  - no DB query on Main (grep for `paged` calls outside `withContext(Dispatchers.IO)`);
  - `pagedChannelStoreCount()` caching stays (ANR guard);
  - large-list EPG gating intact (`> LargeIptvListChannelCount` branches).

**5. `EpgGrid.kt`**
- F7 core lives here. Keep:
  - `ChannelWindowPrefetchThreshold = 24` (F7.2; never restore 10);
  - the scope-aware reset effect with `appliedScrollScope` (F7.1): it repositions ONLY when
    `appliedScrollScope != scrollResetKey` — fresh composition lands on the selected channel's row,
    real scope switch lands at row 0. Window growth/slides must never scroll the list;
  - the `focusSelectedChannelSignal` effect that retries until the target row exists, then marks the
    signal handled (so later appends don't snap the viewport).
- Do NOT reintroduce `didPositionInitialSelection` or any `scrollToItem(0)` keyed on
  `channelWindowIdentity` — that was the exact bug F7.1 removed.
- If upstream adds parameters to `EpgGrid`, accept them; just keep the reset-effect semantics above.
- Keep the grid-scoped clock (F4.2).

**6. `LiveCategory.kt`, `MiniPlayer.kt`, `IptvPlaybackUrlResolver.kt`, `IptvModels.kt`**
- Low conflict. Rules: keep `@Immutable` annotations (F4.1); keep `appendPagedStartupChannelState`
  (F3.1); keep internal clocks (F4.2); keep persistent probe cache in prefs `arvio_iptv_probe_cache`
  (F5.3).

### 6.4 Agent runbook for a rebase
1. `git fetch origin && git rebase origin/main` (or the team's integration branch).
   Expect conflicts mostly on the F-phase commits touching `LiveTvScreen.kt`.
2. For each conflicting commit: `git status`; open each conflicted file and locate our hunks by their
   `// IPTV-PERF F…` markers (§6.2). Resolve per §6.3 rules; prefer *merge both behaviors* over
   dropping either side; when in doubt, keep the larger-list-guard variant.
3. Marker check — nothing lost:
   ```bash
   grep -rn "IPTV-PERF F" app/src/main/kotlin/com/arflix/tv
   ```
   Compare against the §6.2 ledger file-by-file.
4. F7 behavioral assertions (all must pass):
   ```bash
   # scope-only reset key at BOTH EpgGrid call sites (expect 2 hits, none containing filteredChannelsWindowKey)
   grep -n 'scrollResetKey = ' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt
   grep -n 'filteredChannelsWindowKey' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt  # must NOT appear in any scrollResetKey line
   grep -n 'appliedScrollScope' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt
   grep -n 'ChannelWindowPrefetchThreshold = 24' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt
   grep -cn 'didPositionInitialSelection' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/EpgGrid.kt   # must be 0
   grep -n 'lastFilteredSize' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt
   grep -n 'GuidePagedLoadStepRows).coerceAtLeast(0)' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt  # F7.3 gate (1 hit)
   grep -n '!isNewScope &&' app/src/main/kotlin/com/arflix/tv/ui/screens/tv/live/LiveTvScreen.kt             # F7.4 group-counts cache gate (1 hit)
   ```
5. General assertions (older phases): `withContext(Dispatchers.IO)` around `pagedChannelsByIds`;
   `< 10_000` / `> 10_000` guards present; no `TRIM(group_title)` in `IptvChannelStore` window
   queries; `targetOffsetMs(3_000)`; probe client timeouts 1.5s/2s/2.5s.
6. Build + tests (no device needed):
   ```bash
   ./gradlew :app:compilePlayDebugKotlin :app:testPlayDebugUnitTest :app:detekt
   ./gradlew :app:assemblePlayDebug
   ```
7. If upstream already shipped an equivalent fix (e.g., their own `id` index or their own
   scroll-position fix), **dedupe**: prefer the code that covers more cases (ours: index on
   `(source_key, id)` + `group_norm` + scope-aware scroll reset) or adopt theirs and drop ours via
   `git rebase --skip` — noting it in the rebase summary message. Never silently keep both versions
   of the same mechanism (e.g., two scroll resets).
8. Report: list of conflicted commits → resolution chosen per file → marker ledger diff → final
   build/test status → the F7 assertion grep outputs.

### 6.5 What the agent must NOT do during rebase
- `git push -f` without explicit approval; reordering/squashing our commits; resolving conflicts by
  blindly taking "theirs" or "ours".
- Remove `System.err.println("[IPTV-PERF …")` / `IptvPerfTracer` lines (field instrumentation).
- Regenerate the DB (`replaceAll`) on Main; re-introduce a sync probe on the zap hot path.
- Re-add `filteredChannelsWindowKey` or `normalizedGuideStart` to `scrollResetKey`.
- Restore `ChannelWindowPrefetchThreshold` below 24 or the 48-row append gate (`GuidePageRows`) in
  `requestGuideWindowAfter`.
- Re-add `didPositionInitialSelection` or any scroll-to-top effect keyed on the window identity.
- Move the F7.5 warm-up effect above the `selectedCategoryTotalCount` declaration.
- Make `pagedPlaylistGroupCounts()` run on every append again (must stay gated by `isNewScope`).