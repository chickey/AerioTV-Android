"""
AerioTV Dispatcharr pairing + sync plugin.

Dispatcharr's documented plugin API is settings/actions based. AerioTV needs a
small unauthenticated LAN API for discovery and pairing, so this plugin registers
Django REST Framework routes at runtime when the plugin is enabled. That keeps it
installable as a normal Dispatcharr plugin while making the route needs explicit
for an eventual upstream/core extension.

It also serves an admin web page (a Plex-style "Link Account" screen plus a
paired-devices view) at ``/api/plugins/aeriotv/admin`` because Dispatcharr's
plugin settings pane can only render fields + buttons, not custom screens. The
admin page's approve/revoke endpoints require a Dispatcharr admin (is_staff)
session; the equivalent plugin actions remain as a fallback.

Phase 6 hardening:
- Device tokens are stored hashed (sha256). The plaintext token is returned to
  AerioTV exactly once, on the first approved poll, then dropped from state.
- Devices can be revoked; revoked tokens fail sync auth and the pairing status
  becomes ``revoked``.
- The sync document is revisioned (optimistic concurrency, ``409`` on stale PUT).
- ``POST /pairing/start`` is rate limited per client address.
- Logs never contain tokens or pairing codes.
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

PLUGIN_VERSION = "0.5.6"
ADMIN_TOKEN_TTL_SECONDS = 30 * 24 * 3600
# Bumped when the pairing/sync wire contract changes. AerioTV checks this so it
# can warn about an incompatible plugin instead of failing opaquely.
PROTOCOL_VERSION = 2
STATE_DIR = os.environ.get("AERIOTV_PLUGIN_STATE_DIR", "/app/data/plugins/aeriotv")
STATE_FILE = os.path.join(STATE_DIR, "state.json")
PAIRING_TTL_SECONDS = 300
POLL_INTERVAL_SECONDS = 2
# Conservative anti-abuse window for unauthenticated pairing starts.
RATE_LIMIT_WINDOW_SECONDS = 300
RATE_LIMIT_MAX_STARTS = 10
ADMIN_PATH = "/api/plugins/aeriotv/admin"
_state_lock = threading.Lock()
_routes_registered = False


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclass
class PairingSession:
    pairing_id: str
    code: str
    device_name: str
    device_type: str
    app_version: str
    capabilities: list[str]
    remote_addr: str
    user_agent: str
    created_at: float
    expires_at: float
    status: str = "pending"
    device_id: str | None = None
    profile_id: int | None = None
    server_base_url: str | None = None
    # Plaintext token held only between approval and the first approved poll.
    pending_token: str | None = None
    token_delivered: bool = False

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "PairingSession":
        # Tolerate older state rows that predate Phase 6 fields.
        known = {f for f in cls.__dataclass_fields__}  # type: ignore[attr-defined]
        return cls(**{key: value for key, value in data.items() if key in known})

    def to_dict(self) -> dict[str, Any]:
        return self.__dict__.copy()


@dataclass
class AerioTvState:
    pairings: dict[str, PairingSession] = field(default_factory=dict)
    devices: dict[str, dict[str, Any]] = field(default_factory=dict)
    revision: int = 0
    rate: dict[str, dict[str, Any]] = field(default_factory=dict)
    admin_token_hash: str | None = None
    admin_token_expires: float = 0
    sync: dict[str, Any] = field(default_factory=lambda: {
        "schemaVersion": 1,
        "updatedAt": "1970-01-01T00:00:00Z",
        "settings": {},
        "favourites": [],
        "hiddenGroups": [],
        "recentChannels": [],
        "watchProgress": {},
    })

    @classmethod
    def load(cls) -> "AerioTvState":
        with _state_lock:
            if not os.path.exists(STATE_FILE):
                return cls()
            with open(STATE_FILE, "r", encoding="utf-8") as handle:
                raw = json.load(handle)
        return cls(
            pairings={
                key: PairingSession.from_dict(value)
                for key, value in raw.get("pairings", {}).items()
            },
            devices=raw.get("devices", {}),
            revision=int(raw.get("revision", 0) or 0),
            rate=raw.get("rate", {}),
            admin_token_hash=raw.get("admin_token_hash"),
            admin_token_expires=float(raw.get("admin_token_expires", 0) or 0),
            sync=raw.get("sync", cls().sync),
        )

    def save(self) -> None:
        os.makedirs(STATE_DIR, exist_ok=True)
        raw = {
            "pairings": {
                key: value.to_dict()
                for key, value in self.pairings.items()
            },
            "devices": self.devices,
            "revision": self.revision,
            "rate": self.rate,
            "admin_token_hash": self.admin_token_hash,
            "admin_token_expires": self.admin_token_expires,
            "sync": self.sync,
        }
        tmp = f"{STATE_FILE}.tmp"
        with _state_lock:
            with open(tmp, "w", encoding="utf-8") as handle:
                json.dump(raw, handle, indent=2, sort_keys=True)
            os.replace(tmp, STATE_FILE)

    def cleanup_expired(self) -> int:
        now = time.time()
        expired = [
            pairing_id
            for pairing_id, pairing in self.pairings.items()
            if pairing.status == "pending" and pairing.expires_at <= now
        ]
        for pairing_id in expired:
            self.pairings[pairing_id].status = "expired"
        return len(expired)

    def rate_limited(self, remote_addr: str) -> bool:
        """Record a pairing-start attempt and report if it exceeds the window."""
        now = time.time()
        bucket = self.rate.get(remote_addr)
        if not bucket or now - bucket.get("windowStart", 0) > RATE_LIMIT_WINDOW_SECONDS:
            self.rate[remote_addr] = {"windowStart": now, "count": 1}
            return False
        bucket["count"] = int(bucket.get("count", 0)) + 1
        return bucket["count"] > RATE_LIMIT_MAX_STARTS

    def device_for_token(self, token: str) -> str | None:
        if not token:
            return None
        token_hash = _hash_token(token)
        for device_id, device in self.devices.items():
            if device.get("revoked"):
                continue
            if device.get("tokenHash") == token_hash:
                device["lastSeenAt"] = _iso_now()
                return device_id
        return None


class Plugin:
    name = "AerioTV Pairing"
    version = PLUGIN_VERSION
    description = (
        "Pairs AerioTV Fire TV clients with Dispatcharr and serves the AerioTV "
        "sync document. Use the link below to open the pairing page. "
        "Docs: https://github.com/chickey/AerioTV-Android"
    )
    author = "AerioTV"
    # Root-relative so it resolves to the same host/port Dispatcharr is on and
    # opens the pairing admin page (Link Device / Paired Devices).
    help_url = ADMIN_PATH

    fields = [
        {"id": "enabled", "label": "Enable AerioTV pairing endpoints", "type": "boolean", "default": True},
        {
            "id": "server_base_url",
            "label": "Server base URL",
            "type": "string",
            "default": "",
            "placeholder": "e.g. http://tvserver.local:9191",
            "help_text": (
                "Returned to AerioTV after pairing so the app knows how to reach Dispatcharr. "
                "Leave blank to use the address of this Dispatcharr server."
            ),
        },
        {
            "id": "api_key_override",
            "label": "API key override",
            "type": "string",
            "default": "",
            "input_type": "password",
            "help_text": (
                "The Dispatcharr API key returned to AerioTV on approval. "
                "Leave blank — the plugin automatically uses the approving admin's key. "
                "Only set this if auto-detection fails."
            ),
        },
    ]

    # All device management (approve, revoke, delete) is done through the pairing
    # page linked above (help_url). Plugin actions can only show disappearing toasts
    # so they are not used here.
    actions = []

    def __init__(self) -> None:
        register_routes()

    def run(self, action: str, params: dict, context: dict) -> dict:
        # No actions are defined; this method should never be called.
        # Re-register routes defensively in case Dispatcharr reloaded the module.
        register_routes()
        return {"status": "error", "message": f"Unknown action: {action}"}


# --- Shared pairing/device operations (used by plugin actions and admin page) ---


def approve_code(
    state: AerioTvState,
    code: str,
    profile_id: int = 1,
    server_base_url: str | None = None,
    api_key: str = "",
) -> dict[str, Any]:
    pairing = _find_pending_by_code(state, code)
    if not pairing:
        return {"status": "error", "message": f"No pending pairing found for code {code}."}
    token = (api_key or "").strip() or f"aeriotv_{secrets.token_urlsafe(32)}"
    pairing.status = "approved"
    pairing.device_id = f"aeriotv-{uuid.uuid4().hex[:12]}"
    pairing.profile_id = profile_id
    pairing.server_base_url = server_base_url
    pairing.pending_token = token
    pairing.token_delivered = False
    state.devices[pairing.device_id] = {
        "pairingId": pairing.pairing_id,
        "deviceName": pairing.device_name,
        "deviceType": pairing.device_type,
        "appVersion": pairing.app_version,
        "profileId": profile_id,
        "tokenHash": _hash_token(token),
        "pairedAt": _iso_now(),
        "lastSeenAt": None,
        "revoked": False,
    }
    state.save()
    return {
        "status": "ok",
        "message": f"Approved {pairing.device_name}. AerioTV should connect within a few seconds.",
        "deviceId": pairing.device_id,
        "deviceName": pairing.device_name,
    }


def revoke_device_by_id(state: AerioTvState, device_id: str) -> dict[str, Any]:
    device = state.devices.get(device_id)
    if not device:
        return {"status": "error", "message": f"No paired device found with ID {device_id}."}
    device["revoked"] = True
    device["tokenHash"] = None
    device["revokedAt"] = _iso_now()
    for pairing in state.pairings.values():
        if pairing.device_id == device_id:
            pairing.status = "revoked"
            pairing.pending_token = None
    state.save()
    return {"status": "ok", "message": f"Revoked {device.get('deviceName') or device_id}."}


def pending_list(state: AerioTvState) -> list[dict[str, Any]]:
    return [
        {
            "code": pairing.code,
            "deviceName": pairing.device_name,
            "deviceType": pairing.device_type,
            "appVersion": pairing.app_version,
            "remoteAddr": pairing.remote_addr,
            "expiresInSeconds": max(0, int(pairing.expires_at - time.time())),
        }
        for pairing in state.pairings.values()
        if pairing.status == "pending"
    ]


def device_list(state: AerioTvState) -> list[dict[str, Any]]:
    return [
        {
            "deviceId": device_id,
            "deviceName": device.get("deviceName"),
            "deviceType": device.get("deviceType"),
            "appVersion": device.get("appVersion"),
            "profileId": device.get("profileId"),
            "pairedAt": device.get("pairedAt"),
            "lastSeenAt": device.get("lastSeenAt"),
            "revoked": bool(device.get("revoked")),
        }
        for device_id, device in sorted(state.devices.items())
    ]


def delete_device_by_id(state: AerioTvState, device_id: str) -> dict[str, Any]:
    """Permanently remove a device (and its pairing) from state.

    Only allowed on revoked devices; active devices must be revoked first.
    """
    device = state.devices.get(device_id)
    if not device:
        return {"status": "error", "message": f"No device found with ID {device_id}."}
    if not device.get("revoked"):
        return {"status": "error", "message": f"Device {device_id} is still active. Revoke it first."}
    del state.devices[device_id]
    # Remove associated pairing records too.
    stale = [pid for pid, p in state.pairings.items() if p.device_id == device_id]
    for pid in stale:
        del state.pairings[pid]
    state.save()
    return {"status": "ok", "message": f"Deleted {device.get('deviceName') or device_id}."}


def cleanup_revoked_devices(state: AerioTvState) -> dict[str, Any]:
    """Delete all revoked devices and their pairing records."""
    revoked_ids = [did for did, d in list(state.devices.items()) if d.get("revoked")]
    for device_id in revoked_ids:
        del state.devices[device_id]
    stale_pairings = [
        pid for pid, p in list(state.pairings.items())
        if p.device_id and p.device_id not in state.devices
    ]
    for pid in stale_pairings:
        del state.pairings[pid]
    state.save()
    return {"status": "ok", "deleted": len(revoked_ids), "message": f"Deleted {len(revoked_ids)} revoked device(s)."}


def register_routes() -> None:
    global _routes_registered
    if _routes_registered:
        return
    try:
        from django.urls import path, clear_url_caches
        from django.http import HttpResponse
        from rest_framework.response import Response
        from rest_framework.views import APIView
        from apps.plugins import api_urls
    except Exception:
        # Allows local py_compile/tests outside Dispatcharr's Django runtime.
        return

    class CapabilitiesView(APIView):
        authentication_classes = []
        permission_classes = []

        def get(self, request):
            return Response({
                "service": "dispatcharr-aeriotv",
                "pluginVersion": PLUGIN_VERSION,
                "protocolVersion": PROTOCOL_VERSION,
                "dispatcharrVersion": _dispatcharr_version(),
                "pairingSupported": True,
                "syncSupported": True,
                "syncRevisioned": True,
                "pairingEndpoint": "/api/plugins/aeriotv/pairing/start",
            })

    class PairingStartView(APIView):
        authentication_classes = []
        permission_classes = []

        def post(self, request):
            data = request.data or {}
            now = time.time()
            state = AerioTvState.load()
            state.cleanup_expired()
            remote_addr = _remote_addr(request)
            if state.rate_limited(remote_addr):
                state.save()
                return Response(
                    {"error": "rate_limited", "retryAfterSeconds": RATE_LIMIT_WINDOW_SECONDS},
                    status=429,
                )
            pairing_id = uuid.uuid4().hex
            code = _new_code()
            while any(p.code == code and p.status == "pending" for p in state.pairings.values()):
                code = _new_code()
            state.pairings[pairing_id] = PairingSession(
                pairing_id=pairing_id,
                code=code,
                device_name=str(data.get("deviceName") or "AerioTV Fire TV"),
                device_type=str(data.get("deviceType") or "fire_tv"),
                app_version=str(data.get("appVersion") or ""),
                capabilities=list(data.get("capabilities") or []),
                remote_addr=remote_addr,
                user_agent=request.headers.get("User-Agent", ""),
                created_at=now,
                expires_at=now + PAIRING_TTL_SECONDS,
            )
            state.save()
            return Response({
                "pairingId": pairing_id,
                "code": code,
                "expiresAt": _iso_from_epoch(now + PAIRING_TTL_SECONDS),
                "pollIntervalSeconds": POLL_INTERVAL_SECONDS,
            })

    class PairingStatusView(APIView):
        authentication_classes = []
        permission_classes = []

        def get(self, request, pairing_id):
            state = AerioTvState.load()
            state.cleanup_expired()
            pairing = state.pairings.get(pairing_id)
            if not pairing:
                return Response({"status": "expired"}, status=404)
            if pairing.status == "approved":
                payload = {
                    "status": "approved",
                    "serverBaseUrl": pairing.server_base_url or _request_base_url(request),
                    "deviceId": pairing.device_id,
                    "profileId": pairing.profile_id,
                    "sync": state.sync,
                    "revision": str(state.revision),
                }
                # Deliver the plaintext token exactly once, then drop it.
                if not pairing.token_delivered and pairing.pending_token:
                    payload["deviceToken"] = pairing.pending_token
                    pairing.token_delivered = True
                    pairing.pending_token = None
                    state.save()
            else:
                payload = {"status": pairing.status}
            return Response(payload)

    class SyncView(APIView):
        authentication_classes = []
        permission_classes = []

        def get(self, request):
            state = AerioTvState.load()
            device_id = state.device_for_token(_auth_token(request))
            if not device_id:
                return Response({"error": "unauthorized"}, status=401)
            state.save()
            return Response({"revision": str(state.revision), "sync": state.sync})

        def put(self, request):
            state = AerioTvState.load()
            device_id = state.device_for_token(_auth_token(request))
            if not device_id:
                return Response({"error": "unauthorized"}, status=401)
            body = dict(request.data or {})
            # Accept either {baseRevision, sync} or a bare document (legacy).
            incoming = dict(body.get("sync") or (body if "settings" in body else {}))
            base_revision = body.get("baseRevision")
            if base_revision is not None and str(base_revision) != str(state.revision):
                return Response(
                    {"error": "conflict", "revision": str(state.revision), "sync": state.sync},
                    status=409,
                )
            incoming.setdefault("schemaVersion", 1)
            incoming["updatedAt"] = incoming.get("updatedAt") or _iso_now()
            incoming["updatedByDeviceId"] = device_id
            state.sync = _merge_sync_documents(state.sync, incoming)
            state.revision += 1
            state.save()
            return Response({"revision": str(state.revision), "sync": state.sync})

    # --- Admin web page (Dispatcharr admin only) -------------------------------

    class AdminPageView(APIView):
        authentication_classes = []
        permission_classes = []

        def get(self, request):
            response = HttpResponse(_ADMIN_HTML, content_type="text/html")
            # No-store so browsers never serve a stale build of this page while
            # the plugin is being iterated on.
            response["Cache-Control"] = "no-store, max-age=0"
            return response

    # These views deliberately return HTTP 200 with an "authenticated" flag
    # instead of 401/403, and authorise via a plugin-minted token (not
    # Dispatcharr's session/JWT). That guarantees the page can never trip
    # Dispatcharr's logout-on-401 behaviour while it polls.
    class AdminStateView(APIView):
        authentication_classes = []
        permission_classes = []

        def get(self, request):
            state = AerioTvState.load()
            if not _is_admin(request, state):
                return Response({"authenticated": False})
            state.cleanup_expired()
            state.save()
            return Response({
                "authenticated": True,
                "pluginVersion": PLUGIN_VERSION,
                "pending": pending_list(state),
                "devices": device_list(state),
            })

    class AdminApproveView(APIView):
        authentication_classes = []
        permission_classes = []

        def post(self, request):
            state = AerioTvState.load()
            if not _is_admin(request, state):
                return Response({"status": "error", "authenticated": False,
                                 "message": "Admin link expired or invalid. Run 'Open pairing admin page' again."})
            data = request.data or {}
            code = str(data.get("code") or "").strip()
            if not code:
                return Response({"status": "error", "authenticated": True, "message": "Enter a pairing code."})
            # Token returned to AerioTV, in priority order:
            #   1. an explicit key pasted in the form, else
            #   2. the approving admin's own Dispatcharr API key (minted if none),
            #      so AerioTV can call the normal channel/EPG APIs, else
            #   3. a plugin-minted placeholder (sync works; channel APIs will 401).
            api_key = str(data.get("apiKey") or "").strip()
            if not api_key:
                # Try JWT-authed user first (page opened while logged into Dispatcharr).
                user = _admin_user(request)
                if user is not None:
                    api_key = _user_api_key(user) or ""
            if not api_key:
                # Fallback: JWT not found in browser (Dispatcharr stores token under
                # an unexpected localStorage key). Query the DB directly for any
                # admin's API key — same result, no JWT needed.
                api_key = _admin_api_key_from_db() or ""
            state.cleanup_expired()
            result = approve_code(
                state,
                code,
                profile_id=_safe_int(data.get("profileId"), default=1),
                server_base_url=str(data.get("serverBaseUrl") or "").strip() or None,
                api_key=api_key,
            )
            result["authenticated"] = True
            # Tell the page whether a real key was used so we can debug 401s.
            result["tokenSource"] = "real" if api_key and not api_key.startswith("aeriotv_") else "placeholder"
            return Response(result)

    class AdminRevokeView(APIView):
        authentication_classes = []
        permission_classes = []

        def post(self, request):
            state = AerioTvState.load()
            if not _is_admin(request, state):
                return Response({"status": "error", "authenticated": False,
                                 "message": "Admin link expired or invalid. Run 'Open pairing admin page' again."})
            data = request.data or {}
            device_id = str(data.get("deviceId") or "").strip()
            if not device_id:
                return Response({"status": "error", "authenticated": True, "message": "Missing device ID."})
            result = revoke_device_by_id(state, device_id)
            result["authenticated"] = True
            return Response(result)

    class AdminDeleteView(APIView):
        authentication_classes = []
        permission_classes = []

        def post(self, request):
            state = AerioTvState.load()
            if not _is_admin(request, state):
                return Response({"status": "error", "authenticated": False,
                                 "message": "Admin link expired or invalid. Run 'Open pairing admin page' again."})
            data = request.data or {}
            device_id = str(data.get("deviceId") or "").strip()
            if device_id:
                result = delete_device_by_id(state, device_id)
            else:
                # No specific device — delete all revoked.
                result = cleanup_revoked_devices(state)
            result["authenticated"] = True
            return Response(result)

    route_specs = [
        ("aeriotv/capabilities", CapabilitiesView.as_view(), "aeriotv-capabilities"),
        ("aeriotv/pairing/start", PairingStartView.as_view(), "aeriotv-pairing-start"),
        ("aeriotv/pairing/<str:pairing_id>", PairingStatusView.as_view(), "aeriotv-pairing-status"),
        ("aeriotv/sync", SyncView.as_view(), "aeriotv-sync"),
        ("aeriotv/admin", AdminPageView.as_view(), "aeriotv-admin"),
        ("aeriotv/admin/state", AdminStateView.as_view(), "aeriotv-admin-state"),
        ("aeriotv/admin/approve", AdminApproveView.as_view(), "aeriotv-admin-approve"),
        ("aeriotv/admin/revoke", AdminRevokeView.as_view(), "aeriotv-admin-revoke"),
        ("aeriotv/admin/delete", AdminDeleteView.as_view(), "aeriotv-admin-delete"),
    ]
    existing = {getattr(pattern, "name", None) for pattern in api_urls.urlpatterns}
    for route, view, name in route_specs:
        if name not in existing:
            api_urls.urlpatterns.append(path(route, view, name=name))
    clear_url_caches()
    _routes_registered = True


def _find_pending_by_code(state: AerioTvState, code: str) -> PairingSession | None:
    now = time.time()
    for pairing in state.pairings.values():
        if pairing.code == code and pairing.status == "pending" and pairing.expires_at > now:
            return pairing
    return None


def _request_admin_token(request) -> str:
    """Admin page token from the header (fetch calls) or the ?token= query (load)."""
    header = ""
    try:
        header = request.headers.get("X-AerioTV-Admin", "").strip()
    except Exception:
        header = ""
    if header:
        return header
    try:
        return (request.query_params.get("token") or "").strip()
    except Exception:
        try:
            return (request.GET.get("token") or "").strip()
        except Exception:
            return ""


def _staff_via_jwt(request) -> bool:
    """
    True if the request carries a valid Dispatcharr staff JWT (the admin page
    reads it from the logged-in browser's localStorage and sends it as a Bearer
    token). We validate it manually and swallow all errors so this never raises a
    401 back to the browser -- that is what used to log the admin out.
    """
    try:
        from rest_framework_simplejwt.authentication import JWTAuthentication
    except Exception:
        return False
    try:
        result = JWTAuthentication().authenticate(request)
        if not result:
            return False
        user, _ = result
        return bool(user and getattr(user, "is_staff", False))
    except Exception:
        return False


def _admin_user(request):
    """The authenticated Dispatcharr staff User behind this request, or None.

    Tries the SimpleJWT bearer token first (the admin page sends the browser's
    logged-in JWT), then Django's session user. Never raises.
    """
    try:
        from rest_framework_simplejwt.authentication import JWTAuthentication
        result = JWTAuthentication().authenticate(request)
        if result:
            user, _ = result
            if user and getattr(user, "is_staff", False):
                return user
    except Exception:
        pass
    try:
        django_request = getattr(request, "_request", request)
        user = getattr(django_request, "user", None)
        if user and user.is_authenticated and getattr(user, "is_staff", False):
            return user
    except Exception:
        pass
    return None


def _user_api_key(user) -> str | None:
    """Return the user's Dispatcharr personal API key, minting one if absent.

    Mirrors Dispatcharr's own key generation (apps/accounts/api_views.py:
    secrets.token_urlsafe(40) stored on User.api_key) so AerioTV can call the
    normal Dispatcharr APIs (channels/EPG) with a real, user-scoped key.
    """
    try:
        existing = getattr(user, "api_key", None)
        if existing:
            return existing
        key = secrets.token_urlsafe(40)
        user.api_key = key
        user.save(update_fields=["api_key"])
        return key
    except Exception:
        return None


def _admin_api_key_from_db() -> str | None:
    """Fetch a real Dispatcharr API key when no request/JWT is available.

    Used when the admin page is opened from a tokenised plugin link without a
    browser JWT. Queries the User model directly for any staff user with an API
    key, minting one if none exist yet.
    """
    try:
        from apps.accounts.models import User
        user = (
            User.objects.filter(is_staff=True)
            .exclude(api_key__isnull=True)
            .exclude(api_key="")
            .first()
        )
        if user is None:
            # No admin has a key yet — mint one for the first staff user.
            user = User.objects.filter(is_staff=True).first()
        if user is None:
            return None
        return _user_api_key(user)
    except Exception:
        return None


def _valid_admin_token(request, state: AerioTvState) -> bool:
    if not state.admin_token_hash:
        return False
    if state.admin_token_expires and time.time() > state.admin_token_expires:
        return False
    token = _request_admin_token(request)
    if not token:
        return False
    return _hash_token(token) == state.admin_token_hash


def _is_admin(request, state: AerioTvState) -> bool:
    """
    Authorise the admin page without ever emitting a 401 (which would trip
    Dispatcharr's logout-on-401). Two accepted credentials:

    1. A valid Dispatcharr staff JWT in the Authorization header -- the page
       picks this up from the browser it is already logged into, so the docs-style
       link "just works".
    2. A plugin-minted token (the "Open pairing admin page" action), as a fallback
       for session-only setups or bookmarked links.
    """
    return _staff_via_jwt(request) or _valid_admin_token(request, state)


def _auth_token(request) -> str:
    auth = request.headers.get("Authorization", "")
    if auth.lower().startswith("bearer "):
        return auth.split(" ", 1)[1].strip()
    return request.headers.get("X-API-Key", "").strip()


def _merge_sync_documents(current: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
    merged = dict(current or {})
    merged["schemaVersion"] = max(int(merged.get("schemaVersion") or 1), int(incoming.get("schemaVersion") or 1))
    merged["updatedAt"] = incoming.get("updatedAt") or _iso_now()
    merged["updatedByDeviceId"] = incoming.get("updatedByDeviceId")
    merged["settings"] = {**(merged.get("settings") or {}), **(incoming.get("settings") or {})}
    for key in ("favourites", "hiddenGroups", "recentChannels"):
        values = incoming.get(key)
        if isinstance(values, list):
            merged[key] = values
    merged["watchProgress"] = _merge_watch_progress(
        merged.get("watchProgress") or {},
        incoming.get("watchProgress") or {},
    )
    return merged


def _merge_watch_progress(current: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
    """Keep the newest entry per item by updatedAt timestamp."""
    merged = dict(current)
    for key, value in incoming.items():
        existing = merged.get(key)
        if not existing or str(value.get("updatedAt", "")) >= str(existing.get("updatedAt", "")):
            merged[key] = value
    return merged


def _new_code() -> str:
    return f"{secrets.randbelow(10_000):04d}"


def _safe_int(value: Any, default: int) -> int:
    try:
        return int(value)
    except Exception:
        return default


def _iso_now() -> str:
    return _iso_from_epoch(time.time())


def _iso_from_epoch(value: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(value))


def _remote_addr(request) -> str:
    forwarded = request.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.META.get("REMOTE_ADDR", "")


def _request_base_url(request) -> str:
    return request.build_absolute_uri("/").rstrip("/")


def _dispatcharr_version() -> str | None:
    try:
        from version import __version__
        return __version__
    except Exception:
        return None


_ADMIN_HTML = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AerioTV Pairing</title>
<style>
  :root { --bg:#1f2326; --card:#26292d; --field:#15171a; --text:#e9ecef; --muted:#9aa0a6; --accent:#e5a00d; --danger:#e25b5b; --ok:#4caf76; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif; }
  .wrap { max-width:760px; margin:0 auto; padding:24px 16px 48px; }
  h1 { text-align:center; font-size:34px; margin:18px 0 6px; }
  .sub { text-align:center; color:var(--muted); margin:0 0 22px; }
  .tabs { display:flex; gap:8px; justify-content:center; margin-bottom:18px; }
  .tab { background:transparent; border:1px solid #3a3f44; color:var(--text); padding:9px 16px; border-radius:8px; cursor:pointer; font-size:15px; }
  .tab.active { background:var(--accent); border-color:var(--accent); color:#1a1a1a; font-weight:700; }
  .card { background:var(--card); border-radius:14px; padding:24px; }
  label { display:block; color:var(--muted); font-size:13px; margin:14px 0 6px; }
  input { width:100%; background:var(--field); border:1px solid #34383d; color:var(--text); border-radius:10px; padding:14px; font-size:18px; }
  input.code { text-align:center; letter-spacing:10px; font-size:34px; padding:22px; }
  .btn { width:100%; margin-top:18px; background:var(--accent); color:#1a1a1a; border:none; border-radius:10px; padding:15px; font-size:17px; font-weight:700; cursor:pointer; }
  .btn:disabled { opacity:.6; cursor:default; }
  .adv summary { color:var(--muted); cursor:pointer; margin-top:16px; font-size:13px; }
  .msg { margin-top:16px; padding:12px 14px; border-radius:10px; font-size:14px; display:none; }
  .msg.ok { display:block; background:rgba(76,175,118,.15); color:var(--ok); }
  .msg.err { display:block; background:rgba(226,91,91,.15); color:var(--danger); }
  table { width:100%; border-collapse:collapse; margin-top:8px; }
  th,td { text-align:left; padding:10px 8px; border-bottom:1px solid #34383d; font-size:14px; vertical-align:middle; }
  th { color:var(--muted); font-weight:600; }
  .pill { font-size:12px; padding:2px 8px; border-radius:20px; }
  .pill.active { background:rgba(76,175,118,.18); color:var(--ok); }
  .pill.revoked { background:rgba(226,91,91,.18); color:var(--danger); }
  .small { background:transparent; border:1px solid #4a4f55; color:var(--text); border-radius:8px; padding:6px 12px; cursor:pointer; font-size:13px; }
  .small.danger { border-color:var(--danger); color:var(--danger); }
  .small.accent { border-color:var(--accent); color:var(--accent); }
  .empty { color:var(--muted); text-align:center; padding:20px; }
  .hidden { display:none; }
  code { color:var(--accent); }
</style>
</head>
<body>
<div class="wrap">
  <h1>AerioTV Pairing</h1>
  <p class="sub">Link Fire TV devices to Dispatcharr and manage paired devices.</p>

  <div class="tabs">
    <button class="tab active" data-tab="link" onclick="showTab('link')">Link Device</button>
    <button class="tab" data-tab="devices" onclick="showTab('devices')">Paired Devices</button>
  </div>

  <section id="tab-link" class="card">
    <p class="sub" style="margin-top:0">Enter the 4-digit code shown on the Fire TV app.</p>
    <input id="code" class="code" maxlength="4" inputmode="numeric" autocomplete="off" placeholder="0000">
    <details class="adv">
      <summary>Advanced options</summary>
      <label>Dispatcharr profile ID</label>
      <input id="profileId" value="1">
      <label>Server base URL (optional)</label>
      <input id="serverBaseUrl" placeholder="Leave blank to use this server">
      <label>API key returned to AerioTV (optional)</label>
      <input id="apiKey" type="password" placeholder="Leave blank to use your Dispatcharr account's API key">
    </details>
    <button id="linkBtn" class="btn" onclick="approve()">Link</button>
    <div id="linkMsg" class="msg"></div>

    <div id="pendingBlock" class="hidden">
      <label>Waiting for approval</label>
      <table><tbody id="pendingBody"></tbody></table>
    </div>
  </section>

  <section id="tab-devices" class="card hidden">
    <table>
      <thead><tr><th>Device</th><th>Last seen</th><th>Status</th><th></th></tr></thead>
      <tbody id="devicesBody"></tbody>
    </table>
    <div id="devicesEmpty" class="empty">No devices paired yet.</div>
    <div id="cleanupRow" class="hidden" style="margin-top:14px;text-align:right">
      <button class="small danger" onclick="deleteAllRevoked()">Delete all revoked</button>
    </div>
  </section>
</div>

<script>
// Primary auth: the Dispatcharr JWT this browser is already logged in with
// (same origin, so we can read it from localStorage). Fallback: a plugin-minted
// token passed as ?token= in a bookmarked link.
function isJwt(v){ return typeof v==='string' && /^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(v); }
function findJwt(){
  function dig(v){
    if(isJwt(v)) return v;
    try{ const o=JSON.parse(v); for(const k of ['access','accessToken','access_token','token']){ if(o&&isJwt(o[k])) return o[k]; } }catch(e){}
    return '';
  }
  for(const k of ['access','accessToken','access_token','token','authToken']){
    const hit=dig(localStorage.getItem(k)||''); if(hit) return hit;
  }
  for(let i=0;i<localStorage.length;i++){ const hit=dig(localStorage.getItem(localStorage.key(i))||''); if(hit) return hit; }
  return '';
}
const JWT = findJwt();
const PLUGIN_TOKEN = (function(){
  const p = new URLSearchParams(location.search).get('token');
  if(p){ try{ sessionStorage.setItem('aeriotv_admin', p); }catch(e){} return p; }
  try { return sessionStorage.getItem('aeriotv_admin') || ''; } catch(e){ return ''; }
})();
// Derive the API base from this page's own URL so it works no matter the host,
// port, mount path, or trailing slash.
const BASE = location.pathname.replace(/\/+$/,'');
async function api(suffix, opts){
  opts = opts || {};
  const headers = {'Content-Type':'application/json'};
  if(JWT) headers['Authorization'] = 'Bearer '+JWT;
  if(PLUGIN_TOKEN) headers['X-AerioTV-Admin'] = PLUGIN_TOKEN;
  opts.headers = Object.assign(headers, opts.headers||{});
  opts.credentials = 'same-origin';
  try {
    const res = await fetch(BASE + suffix, opts);
    let body = {};
    try { body = await res.json(); } catch(e){}
    return {ok:res.ok, status:res.status, body};
  } catch(e){
    return {ok:false, status:0, body:null, netError:String(e)};
  }
}
function showTab(name){
  document.querySelectorAll('.tab').forEach(t=>t.classList.toggle('active', t.dataset.tab===name));
  document.getElementById('tab-link').classList.toggle('hidden', name!=='link');
  document.getElementById('tab-devices').classList.toggle('hidden', name!=='devices');
  refresh();
}
function msg(el, text, ok){ el.textContent=text; el.className='msg '+(ok?'ok':'err'); }
function fmtTime(s){ if(!s) return '—'; return s.replace('T',' ').replace('Z',' UTC'); }

let pollTimer=null;
function startPolling(){ if(!pollTimer) pollTimer=setInterval(refresh, 5000); }
function stopPolling(){ if(pollTimer){ clearInterval(pollTimer); pollTimer=null; } }
async function refresh(){
  const r = await api('/state');
  // Endpoints always return 200; an "authenticated:false" flag means sign in.
  if(!r.ok || !r.body || r.body.authenticated===false){
    stopPolling();
    let why;
    if(r.body && r.body.authenticated===false){
      why = "Couldn't authenticate. Open this page from the AerioTV plugin's link while signed in to Dispatcharr (same browser), or use the 'Open pairing admin page' action for a token link.";
    } else {
      why = 'Could not reach the pairing service (HTTP '+r.status+
            (JWT?'; auth token found':'; no auth token found in browser')+
            '). Endpoint: '+BASE+'/state'+(r.netError?(' — '+r.netError):'')+
            '. If HTTP 404, fully restart the Dispatcharr backend.';
    }
    msg(document.getElementById('linkMsg'), why, false);
    return;
  }
  startPolling();
  renderPending(r.body.pending||[]);
  renderDevices(r.body.devices||[]);
}
function renderPending(pending){
  const block=document.getElementById('pendingBlock'), body=document.getElementById('pendingBody');
  body.innerHTML='';
  block.classList.toggle('hidden', pending.length===0);
  pending.forEach(p=>{
    const tr=document.createElement('tr');
    const mins=Math.floor((p.expiresInSeconds||0)/60), secs=(p.expiresInSeconds||0)%60;
    tr.innerHTML='<td><code>'+p.code+'</code> &nbsp;'+(p.deviceName||'Device')+'<br><span style="color:#9aa0a6;font-size:12px">'+(p.remoteAddr||'')+' · expires '+mins+':'+String(secs).padStart(2,'0')+'</span></td>'+
      '<td colspan="2"></td><td style="text-align:right"></td>';
    const btn=document.createElement('button'); btn.className='small accent'; btn.textContent='Approve';
    btn.onclick=()=>{ document.getElementById('code').value=p.code; approve(); };
    tr.lastChild.appendChild(btn);
    body.appendChild(tr);
  });
}
function renderDevices(devices){
  const body=document.getElementById('devicesBody'), empty=document.getElementById('devicesEmpty');
  const cleanup=document.getElementById('cleanupRow');
  body.innerHTML='';
  empty.classList.toggle('hidden', devices.length>0);
  const anyRevoked=devices.some(d=>d.revoked);
  cleanup.classList.toggle('hidden', !anyRevoked);
  devices.forEach(d=>{
    const tr=document.createElement('tr');
    const status=d.revoked?'<span class="pill revoked">revoked</span>':'<span class="pill active">active</span>';
    tr.innerHTML='<td>'+(d.deviceName||d.deviceId)+'<br><span style="color:#9aa0a6;font-size:12px">'+d.deviceId+'</span></td>'+
      '<td>'+fmtTime(d.lastSeenAt)+'</td><td>'+status+'</td><td style="text-align:right"></td>';
    const btn=document.createElement('button');
    if(d.revoked){
      btn.className='small danger'; btn.textContent='Delete';
      btn.onclick=()=>deleteDevice(d.deviceId, d.deviceName);
    } else {
      btn.className='small danger'; btn.textContent='Revoke';
      btn.onclick=()=>revoke(d.deviceId, d.deviceName);
    }
    tr.lastChild.appendChild(btn);
    body.appendChild(tr);
  });
}
async function approve(){
  const el=document.getElementById('linkMsg'), btn=document.getElementById('linkBtn');
  const code=document.getElementById('code').value.trim();
  if(!code){ msg(el,'Enter the code shown on the Fire TV.',false); return; }
  btn.disabled=true;
  const r=await api('/approve',{method:'POST',body:JSON.stringify({
    code, profileId:document.getElementById('profileId').value.trim()||'1',
    serverBaseUrl:document.getElementById('serverBaseUrl').value.trim(),
    apiKey:document.getElementById('apiKey').value.trim()
  })});
  btn.disabled=false;
  const ok = r.ok && r.body && r.body.status==='ok';
  const src = r.body && r.body.tokenSource ? ' (token: '+r.body.tokenSource+')' : '';
  msg(el, (r.body && r.body.message) || (ok?'Linked.':'Could not link.') + src, ok);
  if(ok){ document.getElementById('code').value=''; }
  refresh();
}
async function revoke(deviceId, name){
  if(!confirm('Revoke '+(name||deviceId)+'? Its token will stop working immediately.')) return;
  const r=await api('/revoke',{method:'POST',body:JSON.stringify({deviceId})});
  if(r.body && r.body.authenticated===false){ alert("Admin link expired. Run 'Open pairing admin page' in the plugin again."); return; }
  refresh();
}
async function deleteDevice(deviceId, name){
  if(!confirm('Permanently delete '+(name||deviceId)+'? This cannot be undone.')) return;
  const r=await api('/delete',{method:'POST',body:JSON.stringify({deviceId})});
  if(r.body && r.body.authenticated===false){ alert("Admin link expired. Run 'Open pairing admin page' in the plugin again."); return; }
  refresh();
}
async function deleteAllRevoked(){
  if(!confirm('Delete all revoked devices? This cannot be undone.')) return;
  const r=await api('/delete',{method:'POST',body:JSON.stringify({})});
  if(r.body && r.body.authenticated===false){ alert("Admin link expired. Run 'Open pairing admin page' in the plugin again."); return; }
  refresh();
}
document.getElementById('code').addEventListener('keydown',e=>{ if(e.key==='Enter') approve(); });
refresh();
</script>
</body>
</html>
"""
