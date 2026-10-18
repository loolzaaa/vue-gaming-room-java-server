# TODO and Known Limitations

This document tracks planned improvements and deliberate trade-offs. It is intentionally separate
from the README and the usage guide.

## Metrics and observability

Current behavior: logs only (queue overflow, send errors, rejected handshakes/events).

Planned metrics:

- active WebSocket sessions;
- outbound queue size per session and globally;
- coalesced message count;
- outbound queue overflows;
- send latency and send errors;
- HTTP 429 count;
- handshake rejects by reason;
- game event processing time (per room).

The shape of the metrics API is not fixed yet. Implementation will be added at the end of the
current roadmap, after the gameplay-facing work is stable.

## Load and stress tests

Planned scenarios:

- many rooms with many clients;
- slow clients and queue overflow;
- reconnect storms;
- mixed player/display traffic;
- HTTP rate-limit saturation.

## Reconnect grace period and admin transfer

Current behavior:

- If a member disconnects before the game starts, the member is removed immediately.
- If that member was the admin, admin rights are transferred to the first player. If there is no
  other player, the room is removed.
- If the game has already started, the member is kept, but the admin is still transferred to the
  first player on disconnect.
- A reconnecting user joins as a new member. Player/spectator status, readiness and admin rights
  from the previous session are not restored.

This is acceptable for short-lived, disposable lobbies, but it makes a browser refresh or a transient
network failure destructive: the lobby can lose its admin or disappear, and the original admin
cannot reclaim the role.

Planned reconnect behavior:

- Keep a disconnected member in the room for a configurable grace period (for example `30s`).
- On `join` with the same `userId` and member token within the grace period, restore the same member,
  including admin, player/spectator status and readiness.
- Do not transfer admin rights or remove the member until the grace period expires.
- After the grace period in the lobby: remove the member and transfer admin to the first online
  player; remove the room only if no players remain.
- During a running game: never remove the member; transfer admin only after the grace period or an
  explicit leave action. A reconnecting admin should keep the role if the grace period has not
  expired.
- Prefer a `disconnectedAt` timestamp checked by `RoomCleanTask` over one timer per connection.
- Add a setting such as `vgr.room.disconnect-grace` to `ServerSettings` and `VgrProperties`.
- Keep the client's reconnection flow, but it should reuse the existing member token instead of
  creating a new member.

Tests to add when implementing:

- admin refresh in the lobby within the grace period restores admin and player status;
- non-admin player refresh in the lobby restores player status and readiness;
- admin refresh after the grace period transfers admin to the first remaining player;
- refresh in a room with no other players does not destroy the room within the grace period;
- reconnect during a running game restores the player state and keeps admin rights;
- expiration of the grace period in the lobby removes offline members and eventually cleans an
  empty room.

## Token rotation and revocation (low priority)

Member tokens are stable for the lifetime of the room and are removed together with the member or
room. Explicit rotate/revoke/logout-all endpoints are not implemented.

This is acceptable for short-lived, anonymous board-game rooms. Revisit when rooms become
long-lived or when accounts, payments or valuable inventory are added.

## Token storage and XSS

The reference Vue client stores the member token in `localStorage`. This is an acceptable trade-off
for simple board games. If payments, accounts, valuable inventory or user-generated HTML are added,
treat XSS as a real threat:

- use HttpOnly cookies or SSO;
- enforce a strict CSP;
- sanitize user-generated HTML;
- add token rotation and revocation.
