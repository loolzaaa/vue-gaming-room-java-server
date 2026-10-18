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
