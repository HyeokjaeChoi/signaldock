---
status: accepted
---

# Server runtime and receipt boundaries

The server uses jOOQ Open Source 3.21 on the repository's pinned Java 21 runtime. Normal ingestion uses a four-slot JDBC execution area and a four-connection pool with a 5-second request budget, 250-millisecond admission and lock limits, a 500-millisecond connection limit, and a 1-second statement limit; these are conservative local defaults, not benchmark values, and can change after measurement. The collection key is supplied as one `Authorization: Bearer` value, while contract selection remains in `SignalDock-Contract-Version`.

History: until issue 11 (2026-10-11) the server used jOOQ 3.19, because jOOQ 3.20 and later require Java 21 and the pin was Java 17. Issue 11 moved to jOOQ 3.21.9 on Java 21 (Temurin 21.0.12.1+1). The Java 17 pin that forced jOOQ 3.19 was an implementer choice, not a design decision. The tracked jOOQ sources were regenerated, and `cd server && ./gradlew test verifyJooq --no-daemon` passes. The previous paragraph above stays as history.

An event group is the receipt unit for one `eventId` inside a batch. If any member with that valid ID is semantically invalid, the whole group is rejected as `EVENT_ID_CONFLICT`; this prevents a valid sibling from defining stored content when another request index claims the same ID with content that cannot be compared. Database cancellation cancels the active PostgreSQL query, rolls back, and reuses the connection only after validation succeeds. Timeout responses tell clients to resend the same event IDs because a commit outcome can be uncertain.
