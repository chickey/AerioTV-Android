# AerioTV Dispatcharr Plugin

This plugin pairs AerioTV Fire TV clients with Dispatcharr and serves the AerioTV
sync document.

It provides the endpoint shape expected by the Android app:

- `GET /api/plugins/aeriotv/capabilities`
- `POST /api/plugins/aeriotv/pairing/start`
- `GET /api/plugins/aeriotv/pairing/{pairingId}`
- `GET /api/plugins/aeriotv/sync`
- `PUT /api/plugins/aeriotv/sync`

It also exposes Dispatcharr plugin actions (a fallback for the admin page):

- `List pending pairings`
- `Approve pairing`
- `Clean up expired pairings`
- `List paired devices`
- `Revoke device`

## Admin Web Page

Dispatcharr's plugin settings pane can only render fields and buttons, so the
nicer pairing UI is served as a standalone page from the plugin's own routes,
with two tabs:

- **Link Device** — a big 4-digit code box and a `Link` button, plus a live list
  of devices currently waiting for approval (each with a one-click `Approve`).
- **Paired Devices** — every paired Fire TV, its last-seen time and status, with
  a `Revoke` button.

### Opening it

In Dispatcharr, open the **AerioTV Pairing** plugin and click the **help/docs
link** on the plugin page. The plugin's `help_url` points at
`/api/plugins/aeriotv/admin`, which resolves to the same host/port Dispatcharr
is on, so the link opens the pairing page directly.

### How it authenticates (no login prompt, no token to copy)

The admin page is same-origin with the Dispatcharr web app, so it reads the JWT
your browser is already logged in with (from `localStorage`) and sends it as a
`Bearer` token. The plugin validates it as a Dispatcharr **staff** user. Nothing
to sign into again, nothing to copy.

All admin endpoints return HTTP `200` with an `authenticated` flag (never `401`),
because Dispatcharr's web UI logs you out on any `401` — an earlier version of
this plugin did exactly that while polling.

### Device token for channel/EPG access

So AerioTV can call Dispatcharr's normal channel/EPG APIs after pairing, the
plugin returns the **approving admin's own Dispatcharr API key** as the device
token (read from the logged-in admin's account; minted with
`secrets.token_urlsafe(40)` like Dispatcharr does if the account has none yet).
You can override this by pasting a specific key in the admin page's Advanced
options. If neither is available (e.g. you opened the page via a token link
without a Dispatcharr login), the plugin falls back to a placeholder token —
pairing/sync still work, but channel loading will return `401` until a real key
is supplied.

**Fallback:** if your browser doesn't expose the JWT where the page can find it,
run the **Open pairing admin page** action to get a tokenised link
(`.../admin?token=...`, valid 30 days, bookmarkable). The `Approve pairing` /
`Revoke device` / `List paired devices` plugin actions also remain as a fallback.

> Note: the plugin registers its HTTP routes at runtime, which cannot be
> hot-swapped. After importing a new version of the zip, **fully restart the
> Dispatcharr backend** (restart the container/process) and hard-refresh the
> admin page in your browser, or you may keep hitting the previously loaded code.

## Phase 6 Hardening

- **Hashed tokens.** Device tokens are stored as a sha256 hash in `state.json`.
  The plaintext token is returned to AerioTV exactly once, on the first approved
  poll, then dropped from state.
- **Revocation.** Paste a device ID from `List paired devices` into
  `Device ID to revoke` and run `Revoke device`. Its token stops working
  immediately (sync returns `401`) and the pairing status becomes `revoked`.
- **Sync conflict handling.** `GET /sync` returns `{ "revision", "sync" }`.
  `PUT /sync` accepts `{ "baseRevision", "sync" }` and returns `409` with the
  current state if the revision is stale, so AerioTV can merge and retry. Watch
  progress merges by newest `updatedAt` per item.
- **Rate limiting.** `POST /pairing/start` is limited per client address
  (10 starts / 5 min) and returns `429` when exceeded.
- **Version negotiation.** `capabilities` returns `protocolVersion` and
  `syncRevisioned` so AerioTV can detect an incompatible plugin.

## Important Caveat

Dispatcharr's documented plugin system currently focuses on `plugin.json`, `plugin.py`, settings, and actions. Plugins are loaded from `/app/data/plugins`, and enabled plugins can access Dispatcharr internals, but there is not yet a documented first-class API for plugin-owned unauthenticated HTTP routes.

Because AerioTV needs LAN discovery and first-run pairing before the Fire TV has credentials, this prototype registers Django REST Framework routes at runtime when the plugin is enabled. That is good enough to validate the flow, but it should be treated as a proof-of-concept until Dispatcharr has an official plugin route extension point.

## Install For Testing

Copy the `aeriotv` folder into Dispatcharr's plugin directory:

```bash
cp -R dispatcharr-plugin/aeriotv /path/to/dispatcharr/data/plugins/aeriotv
```

Then in Dispatcharr:

1. Open `Plugins`.
2. Reload plugins.
3. Enable `AerioTV Pairing`.
4. For Phase 4 testing, paste a Dispatcharr API key into `API key returned to AerioTV`.
5. On the Fire TV, choose `Find Dispatcharr automatically`.
6. Enter the code shown by AerioTV into the plugin setting `Pairing code to approve`.
7. Run `Approve pairing`.

The Android app should then see the pairing status become `approved`.

## State Storage

The prototype stores state in:

```text
/app/data/plugins/aeriotv/state.json
```

It contains pending pairings, paired devices, generated device tokens, and a placeholder sync document.

The sync document currently stores:

- settings,
- favourites,
- hidden groups,
- recent channels,
- watch progress.

## Production Notes

Before this is production-grade, we should replace the route-registration shim with one of:

- an official Dispatcharr plugin route API,
- a small upstream Dispatcharr core patch for AerioTV plugin routes,
- or a sanctioned sidecar/service mechanism advertised through Dispatcharr.

For Phase 4 testing, the plugin can return a real Dispatcharr API key as `deviceToken` by setting `API key returned to AerioTV`. That lets the current Android app reuse its existing Dispatcharr API-key code path.

The production token still needs to become one of:

- a scoped Dispatcharr API key,
- a plugin-proxied token,
- or a proper Dispatcharr permission model.
