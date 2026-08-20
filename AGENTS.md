# AGENTS.md — ARVIO Codebase Guide

## Project Overview
ARVIO is a Kotlin/Compose Android TV (+ phone/tablet) media hub. Package namespace is `com.arflix.tv`; the app ID on the Play Store is `com.arvio.tv`. Single Gradle module (`app/`). Hilt DI, MVVM + StateFlow, Jetpack Navigation Compose.

---

## Build Commands
```bash
# Debug builds (choose flavor)
./gradlew assemblePlayDebug          # Play Store flavor (no self-update, no FFmpeg)
./gradlew assembleSideloadDebug      # Sideload flavor (self-update + FFmpeg decoder)

# Release / staging
./gradlew assemblePlayRelease
./gradlew assembleSideloadRelease
./gradlew assembleSideloadStaging    # release config, debug signing, .staging suffix

# Tests & lint
./gradlew test                       # unit tests
./gradlew detekt                     # static analysis (ignoreFailures = true; doesn't block CI)
./gradlew detektBaseline             # regenerate baseline after intentional changes
```

**Note:** R8/minify is intentionally disabled in release (`isMinifyEnabled = false`) for runtime stability.

---

## Secrets Setup
Copy `secrets.defaults.properties` → `secrets.properties` and fill in:
```
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=...
GOOGLE_WEB_CLIENT_ID=...apps.googleusercontent.com
```
TMDB and Trakt API keys live on the server only — never in the app. All calls to `api.themoviedb.org` and `api.trakt.tv` are transparently intercepted by `ApiProxyInterceptor` and rerouted through Supabase Edge Functions (`/functions/v1/tmdb-proxy`, `/functions/v1/trakt-proxy`).

---

## Architecture & Key Directories
```
app/src/main/kotlin/com/arflix/tv/
  data/api/           Retrofit interfaces (TmdbApi, TraktApi, StreamApi, SupabaseApi, …)
  data/model/         Domain models — ALL annotated @Immutable (MediaItem, Episode, Profile, …)
  data/repository/    Business logic singletons injected via Hilt
  di/                 AppModule.kt (Retrofit/OkHttp providers), WorkerModule.kt
  navigation/         AppNavigation.kt — single NavHost, all routes in Screen sealed class
  network/            OkHttpProvider, ApiProxyInterceptor, NetworkMonitor
  ui/components/      Shared Compose components (MediaCard, Sidebar, Toast, StreamSelector, …)
  ui/screens/         One folder per screen; each has a Screen.kt + ViewModel.kt
  ui/skin/            ArvioSkin token system (colors, spacing, radius, typography, motion, focus)
  ui/theme/           Standard Material theme wrappers (Color, Typography, Fonts)
  util/               Result<T>, UiState<T>, Constants, DataStores, DeviceType, AppLogger
  worker/             WorkManager workers (TraktSyncWorker)
```

---

## State & Error Patterns
- **Repository layer** → returns `Result<T>` (`Result.Success` / `Result.Error(AppException)`)
- **ViewModel layer** → exposes `UiState<T>` (`Idle` → `Loading` → `Success` / `Error`)
- Convert with `result.toUiState()` or `UiState.fromResult(result)`
- Never throw from repositories; always wrap in `runCatching { }` (project-local version in `util/Result.kt`)

---

## Profile-Scoped Storage
All DataStore preference keys are namespaced per profile. Use `ProfileManager` to generate keys:
```kotlin
// Pattern used throughout repositories:
private fun catalogsKey(profileId: String) = stringPreferencesKey("profile_${profileId}_catalogs_v1")
```
Four DataStore instances (defined as Context extensions in `util/DataStores.kt`):
`settingsDataStore`, `traktDataStore`, `profilesDataStore`, `authDataStore`

**Never** create new `preferencesDataStore` delegates with the same name in different files — use the ones in `DataStores.kt`.

---

## Navigation Conventions
- Routes live in the `Screen` sealed class in `AppNavigation.kt`; always use `Screen.X.createRoute(...)` to build URLs — string params are `URLEncoder.encode`d there.
- Top-level tab navigation uses `navigateTopLevel()` (pops to Home, `saveState = true`).
- Going home uses `navigateHome()` with `inclusive = true, saveState = false` to prevent stale back-stack entries.
- `Settings` accepts optional `?autoCloudAuth=true` query param (navigate with literal string, not `Screen.Settings.route`).

---

## TV / Device-Type Patterns
- Detect device type via `detectDeviceType(context)` → `DeviceType.TV / TABLET / PHONE`.
- Inject into Compose tree as `LocalDeviceType` and `LocalHasTouchScreen` composition locals.
- For D-pad-compatible interactive elements use the `Modifier.arvioFocusable(...)` extension (`ui/skin/ArvioFocus.kt`) — do **not** use plain `Modifier.clickable` for TV focus rings.
- `androidx.tv:tv-foundation` stays on alpha (no stable release exists); `tv-material` is at stable `1.0.0`.

---

## Design Token System
Use `ArvioSkin` (not raw MaterialTheme) for colors, spacing, and focus styles:
```kotlin
ArvioSkin.colors.accent
ArvioSkin.spacing.x4
ArvioSkin.focus.outlineColor
```
Token values are defined in `ArvioSkinTokens.kt` and provided via `ProvideArvioSkin { }`.
Compose stability is enforced via `app/compose_stability_config.conf` — all new domain models need `@Immutable`.

---

## Key External Integrations
| Service | How it's used |
|---|---|
| Supabase | Auth (Google + email), cloud sync (profiles/addons/catalogs/IPTV), watch history, realtime WebSocket |
| TMDB | Metadata (proxied — no key in app) |
| Trakt.tv | Watch history sync, Trakt catalogs (proxied); background via `TraktSyncWorker` |
| Stremio addons | Stream resolution in `StreamRepository` — addons are user-installed HTTP endpoints |
| Jikan / AniSkip / IntroDB / ARM | Anime metadata & skip-intro timestamps |
| ExoPlayer Media3 | Playback; FFmpeg extension only in `sideload` flavor |

Supabase API client is built **without disk cache** (`cache(null)`) to prevent OkHttp silently swallowing POST/upsert responses.

---

## Image Loading
Coil is configured globally in `ArflixApplication.newImageLoader()`:
- `Bitmap.Config.RGB_565` + `allowRgb565(true)` for TV performance
- 48 MB disk cap, 15% memory cap (OOM prevention during playback)
- `crossfade(false)` and `respectCacheHeaders(false)`
- Use `Precision.INEXACT` on image requests where exact sizing is not required

