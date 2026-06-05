# Dispatcharr Pairing and Sync Design

This document describes a Fire TV-first pairing and sync design for AerioTV using Dispatcharr as the trusted local backend.

The main goal is to avoid entering server hostname, port, username, password, or API keys on every Fire TV device. A secondary goal is to replace Google Drive sync on Fire TV with a Dispatcharr-backed sync provider that can share settings and viewing state across AerioTV clients.

## Goals

- Discover Dispatcharr instances on the same local network.
- Confirm that the AerioTV Dispatcharr plugin is installed before offering one-tap setup.
- Pair a Fire TV device using a short code shown on the TV and entered in the Dispatcharr plugin UI.
- Issue a scoped AerioTV device token without exposing the Dispatcharr admin username/password to the Fire TV.
- Automatically configure AerioTV with the Dispatcharr base URL, auth token, selected profile/user, lineup, EPG, and DVR access.
- Sync AerioTV settings and user state through Dispatcharr instead of Google services on Fire TV.
- Keep Google sync available for Play-oriented builds where it already works.
- Keep manual hostname/IP setup available as a fallback.

## Non-Goals

- Do not require Google Play Services, Google sign-in, or Drive access on Fire TV.
- Do not remove Google sync from the whole repository while the Play build still targets Google Play.
- Do not sync raw EPG or channel lineups as app settings. Dispatcharr remains the authority for lineup, EPG, logos, DVR, and stream URLs.
- Do not store Dispatcharr username/password in AerioTV if a scoped device token can be used instead.
- Do not make the app unusable when sync is unavailable. AerioTV must continue using local settings and cached data.

## Recommended Architecture

Use a Dispatcharr plugin rather than a Dispatcharr integration.

Dispatcharr integrations are best suited for event-driven webhooks or scripts. Pairing and sync need plugin-owned UI, pending-device state, token generation, revocation, and API endpoints, which makes a plugin the better fit.

Keep sync provider support flavour-aware:

- Fire TV build: expose `None` and `Dispatcharr`.
- Play build: expose `None`, `Google Drive`, and optionally `Dispatcharr`.
- Shared code should use a provider abstraction so Google-specific code does not leak into Fire TV setup screens.
- Google sync can remain in the repo for mergeability with upstream/Play-oriented work, but it should be hidden and inactive in Fire TV builds.

```text
AerioTV Fire TV app
  -> local discovery
  -> AerioTV Dispatcharr plugin pairing API
  -> scoped device token
  -> Dispatcharr lineup/EPG/DVR APIs
  -> AerioTV sync document API
```

## Discovery

AerioTV should try discovery in this order:

1. mDNS/Bonjour
   - Preferred service name: `_aeriotv-dispatcharr._tcp.local`
   - Fallback service name: `_dispatcharr._tcp.local`
   - TXT records should include `plugin=aeriotv`, `version`, and `basePath`.

2. Subnet HTTP probe
   - Probe the local subnet for likely Dispatcharr ports.
   - Default port: `9191`
   - Probe endpoint:

```text
GET http://{host}:9191/api/plugins/aeriotv/capabilities
```

3. Manual fallback
   - User enters hostname/IP and port.
   - App probes the capabilities endpoint before offering pairing.

The subnet scan should be conservative on Fire TV:

- Short timeout, for example 300-500 ms per host.
- Limit concurrent probes.
- Stop early when a plugin-capable instance is found unless the user asks to scan again.
- Do not scan public networks or mobile data style networks.

Current app implementation status:

- Phase 1 added flavour-aware provider visibility and the Fire TV pairing screen skeleton.
- Phase 2 adds a real capability discovery client in the Android app.
- The Fire TV pairing screen now probes likely local Dispatcharr hostnames first, then conservatively scans active site-local IPv4 `/24` networks on port `9191`.
- A compatible instance must respond from `/api/plugins/aeriotv/capabilities` with `service=dispatcharr-aeriotv` and `pairingSupported=true`.
- Phase 3 adds a prototype Dispatcharr plugin under `dispatcharr-plugin/aeriotv` and an Android pairing client for `POST /pairing/start` plus `GET /pairing/{pairingId}`.
- The plugin prototype registers Django routes at runtime because Dispatcharr's documented plugin API currently focuses on settings/actions rather than first-class unauthenticated plugin-owned HTTP routes.
- The token returned by the prototype proves the pairing flow only; future work must decide whether it becomes a scoped Dispatcharr API key, a plugin-proxied token, or a first-class Dispatcharr permission model.
- Phase 4 wires approved pairings into AerioTV's existing Dispatcharr playlist save path. The app persists the returned server URL, profile id, and `deviceToken` in the credential slot used by current Dispatcharr API-key flows, then loads channels and EPG normally.
- For prototype testing, the plugin has an `API key returned to AerioTV` setting. When populated, that API key is returned as `deviceToken` so the current Android code can call existing Dispatcharr endpoints immediately after pairing.
- Phase 5 adds manual Dispatcharr sync for Fire TV. The Android app can pull/push settings, favourites, hidden groups, recent channels, and watch progress through `GET/PUT /api/plugins/aeriotv/sync`. The plugin also has a `List paired devices` action.
- Phase 6 (in progress) hardens the plugin (now v0.2.0, protocolVersion 2):
  - Device tokens are stored hashed (sha256). The plaintext token is returned to AerioTV exactly once, on the first approved poll, then dropped from plugin state.
  - Device revocation via a `Revoke device` action; revoked tokens fail sync auth with `401` and the pairing status becomes `revoked`. AerioTV surfaces a "pair again" error.
  - Sync is revisioned: `GET /sync` returns `{revision, sync}`; `PUT /sync` takes `{baseRevision, sync}` and returns `409` with the current state on a stale write. The app merges and retries once. Watch progress merges by newest `updatedAt` per item.
  - `POST /pairing/start` is rate limited per client address (10 / 5 min, `429`).
  - `capabilities` advertises `protocolVersion` and `syncRevisioned` for client compatibility checks.
  - mDNS/NSD discovery: the app resolves `_aeriotv-dispatcharr._tcp` (and the generic `_dispatcharr._tcp`) under a Wi-Fi multicast lock, probing those hosts before the subnet scan. Best-effort; falls back cleanly when nothing is advertised.
  - Encrypted on-device token storage: the scoped device token is stored via `EncryptedSharedPreferences` (Android Keystore) with a silent fallback to ordinary prefs on devices with a flaky Keystore. The sync path prefers this token and clears it when the plugin revokes the device.
  - Decision: Google sync is **kept in the repo and hidden at runtime** in the Fire build (`BuildConfig.GOOGLE_SERVICES_AVAILABLE` / `SyncProviders.visible`) rather than split into a Fire-only source set, to preserve mergeability with the upstream/Play repo. A separate Google-to-Dispatcharr migration is unnecessary: local state is already the source of truth, so switching to Dispatcharr and running Sync now seeds the server from the device's current state.
  - Admin web page: because Dispatcharr's plugin settings pane only renders fields + buttons, the plugin (v0.3.0) also serves a standalone admin page at `/api/plugins/aeriotv/admin` with a Plex-style "Link Device" code entry and a "Paired Devices" tab (revoke per device). Its approve/revoke/state endpoints require a Dispatcharr admin (`IsAdminUser`) session; the plugin actions remain as a fallback.
  - Still outstanding in Phase 6: official Dispatcharr plugin route support (the plugin still registers routes via a runtime DRF shim because Dispatcharr has no sanctioned plugin-route API yet).

## Capabilities Endpoint

The plugin exposes a lightweight unauthenticated capability endpoint so AerioTV can identify compatible Dispatcharr instances.

```text
GET /api/plugins/aeriotv/capabilities
```

Example response:

```json
{
  "service": "dispatcharr-aeriotv",
  "pluginVersion": "1.0.0",
  "dispatcharrVersion": "0.24.0",
  "pairingSupported": true,
  "syncSupported": true,
  "pairingEndpoint": "/api/plugins/aeriotv/pairing/start"
}
```

## Pairing Flow

1. AerioTV finds a compatible Dispatcharr instance.
2. User selects the instance on Fire TV.
3. AerioTV starts pairing.
4. Dispatcharr creates a pending pairing session and returns a 4 digit code.
5. Fire TV displays the code.
6. User opens Dispatcharr web UI, goes to the AerioTV plugin page, and enters the code.
7. Dispatcharr approves the pending device and returns a scoped token.
8. AerioTV stores the server URL, token, device ID, profile/user scope, and initial sync payload.

### Start Pairing

```text
POST /api/plugins/aeriotv/pairing/start
```

Request:

```json
{
  "deviceName": "Fire TV Stick",
  "deviceType": "fire_tv",
  "appVersion": "0.1.3-fire",
  "capabilities": ["live_tv", "dvr", "sync", "multiview"]
}
```

Response:

```json
{
  "pairingId": "01JZ0000000000000000000000",
  "code": "4821",
  "expiresAt": "2026-06-05T20:05:00Z",
  "pollIntervalSeconds": 2
}
```

### Poll Pairing Status

```text
GET /api/plugins/aeriotv/pairing/{pairingId}
```

Pending response:

```json
{
  "status": "pending"
}
```

Approved response:

```json
{
  "status": "approved",
  "serverBaseUrl": "http://tvserver.local:9191",
  "deviceId": "firetv-living-room",
  "deviceToken": "aeriotv_device_token",
  "profileId": 1,
  "sync": {
    "schemaVersion": 1,
    "updatedAt": "2026-06-05T20:00:00Z",
    "settings": {},
    "favourites": [],
    "hiddenGroups": [],
    "recentChannels": [],
    "watchProgress": {}
  }
}
```

Denied or expired response:

```json
{
  "status": "denied"
}
```

```json
{
  "status": "expired"
}
```

## Plugin UI

The plugin should provide:

- Pending pairing requests.
- Code entry field.
- Device name and IP address display.
- Approve and deny actions.
- Paired devices list.
- Revoke device token action.
- Last sync time per device.
- Optional reset sync state action.

Suggested pending request display:

```text
AerioTV Fire TV wants to pair
Code: 4821
Device: Fire TV Stick
IP: 192.168.0.102
Expires: 4:32

Approve / Deny
```

## Authentication

The Fire TV should receive a scoped device token, not the Dispatcharr admin password.

Token requirements:

- Bound to one paired device.
- Revocable from the plugin UI.
- Limited to AerioTV client operations.
- Usable for Dispatcharr APIs needed by AerioTV, or exchangeable server-side by the plugin.
- Stored on device using Android encrypted storage if available, with local fallback for Fire TV compatibility.

Recommended request header:

```text
Authorization: Bearer {deviceToken}
```

If Dispatcharr requires `X-API-Key` for existing endpoints, the plugin can either:

- mint a compatible scoped API key, or
- proxy AerioTV-specific requests through plugin endpoints, or
- return an API key with limited permissions if Dispatcharr supports that model.

Avoid returning the user password.

## Sync Data

The sync payload should be a versioned JSON document per Dispatcharr user/profile. Device-specific overrides can be stored separately if needed.

Initial sync categories:

- App behaviour settings.
- Guide options.
- Appearance options.
- DVR settings that are safe across devices.
- Multiview settings and presets.
- Favourites.
- Hidden groups.
- Recent channels.
- Watch progress.
- Last watched channel.
- Default tab.

Do not sync:

- Raw channel lists.
- Raw EPG data.
- Stream URLs unless they are already part of normal Dispatcharr API access.
- Local recording file paths.
- Keystore or release data.
- Device-only cache state.
- Fire TV network-specific hostname overrides unless explicitly marked device-local.

Example sync document:

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-06-05T20:00:00Z",
  "updatedByDeviceId": "firetv-living-room",
  "settings": {
    "guide": {
      "showChannelName": false,
      "showChannelNumber": false,
      "logoScale": "fill"
    },
    "playback": {
      "skipSeconds": 30,
      "freezeDetectionEnabled": true,
      "freezeDetectionSeconds": 15
    }
  },
  "favourites": [
    "dispatcharr-channel-uuid"
  ],
  "hiddenGroups": [
    "Shopping"
  ],
  "recentChannels": [
    "dispatcharr-channel-uuid"
  ],
  "watchProgress": {
    "recording-uuid": {
      "positionMs": 123000,
      "durationMs": 1800000,
      "updatedAt": "2026-06-05T19:58:00Z"
    }
  }
}
```

## Sync API

```text
GET /api/plugins/aeriotv/sync
PUT /api/plugins/aeriotv/sync
```

The app should send `If-Match` or an equivalent revision value to avoid blind overwrites.

Example response:

```json
{
  "revision": "42",
  "sync": {}
}
```

Example update:

```json
{
  "baseRevision": "42",
  "sync": {}
}
```

Conflict behaviour:

- For simple preferences, latest updated timestamp wins.
- For favourites and hidden groups, merge sets.
- For watch progress, keep the highest updated timestamp per item.
- If a conflict cannot be merged, plugin returns `409 Conflict` and the app pulls, merges locally, then retries.

## Android App Changes

Add a Dispatcharr sync provider alongside the existing Google provider:

```text
Sync provider:
- None
- Google Drive
- Dispatcharr
```

Fire TV builds should prefer Dispatcharr and hide Google entirely. Play builds can keep Google Drive visible and can also offer Dispatcharr if desired.

Implementation detail:

- Gate visible providers by build flavour.
- Keep Google Drive implementation compiled only where required by the Play build if dependency separation is practical.
- If dependency separation is not practical initially, keep the Google code dormant in the Fire build and ensure no Google sign-in path is reachable.
- Do not force the Fire TV APK to carry unnecessary Google setup UX or OAuth configuration.

First-run setup should become:

1. Find Dispatcharr.
2. Select instance.
3. Show pairing code.
4. Wait for plugin approval.
5. Save returned token and profile.
6. Pull sync data.
7. Refresh channels and EPG.
8. Enter Live TV.

Manual setup remains:

1. Enter hostname/IP and port.
2. Enter API key or username/password.
3. Optionally pair/sync later from Settings.

New Android modules/classes likely needed:

- `DispatcharrDiscoveryClient`
- `DispatcharrPairingClient`
- `DispatcharrSyncClient`
- `DispatcharrSyncManager`
- `PairDispatcharrScreen`
- `DispatcharrSyncSettingsScreen`
- secure token storage helper

## Security Notes

- Pairing codes should expire quickly, around 5 minutes.
- Pairing codes should be random and single use.
- The plugin should rate limit pairing attempts.
- The plugin should show pending device IP and user agent if available.
- Device tokens should be revocable.
- Device tokens should be stored hashed server-side.
- Token responses should only be returned once after approval.
- Logs should redact tokens and pairing codes.
- Manual fallback should remain available for networks where discovery fails.

## Implementation Phases

### Phase 1: Contract and Mock Android Client

- Add this design document.
- Add Android data models for capabilities, pairing, and sync payloads.
- Add a mock pairing client for UI testing.
- Build the first-run Fire TV pairing screens without real plugin dependency.
- Add a sync-provider abstraction so Fire TV can hide Google sync while Play builds keep it.

### Phase 2: Android Discovery

- Add local hostname probes.
- Add conservative subnet probe fallback.
- Show found/not-found states in Fire TV onboarding.

### Phase 3: Dispatcharr Plugin Prototype and Real Pairing Calls

- Add prototype plugin package under `dispatcharr-plugin/aeriotv`.
- Implement capabilities endpoint.
- Implement pending pairing sessions.
- Implement pairing approval through plugin settings/actions.
- Implement prototype device token generation.
- Connect Android pairing flow to plugin endpoints.

### Phase 4: Persist Paired Configuration

- Save returned token and configure the Dispatcharr playlist automatically.

### Phase 5: Sync Categories

- Start with app settings, favourites, hidden groups, and recent channels.
- Add watch progress.
- Add multiview presets.
- Add manual sync and sync status UI.

### Phase 6: Hardening

- Add mDNS discovery.
- Add official Dispatcharr plugin route support or upstream route hook.
- Store server-side device tokens hashed.
- Add device revocation.
- Add encrypted token storage.
- Add conflict handling.
- Add better errors for plugin missing, code expired, denied pairing, and token revoked.
- Add plugin/device version checks.
- Add migration from Google sync where possible.
- Split Google sync dependencies out of the Fire build if it can be done cleanly without harming mergeability.

## Open Questions

- Should sync be per Dispatcharr user, per channel profile, or global with device-specific overrides?
- Can Dispatcharr plugin tokens call existing Dispatcharr APIs directly, or should the plugin proxy some calls?
- Should pairing approval select a Dispatcharr user/profile at approval time?
- Should the app support multiple Dispatcharr servers later?
- What is the minimum Dispatcharr version that supports the plugin endpoints we need?

## Preferred Default Decisions

- Use one Dispatcharr server per AerioTV install for the first implementation.
- Pair against one Dispatcharr user/profile.
- Use plugin-scoped device tokens.
- Keep server URL/token local and sync normal app settings through the plugin.
- Keep manual setup as a permanent fallback.
- Avoid syncing device-local network overrides.
- Hide Google sync entirely in Fire TV builds while keeping it available for Play builds.
