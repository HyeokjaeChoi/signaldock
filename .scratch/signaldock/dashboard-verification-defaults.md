# Dashboard defaults for SDK verification

User direction on 2026-10-08: the SDK is the main work, and the Dashboard verifies results. The agent will set detailed defaults using practical use cases from the major vendors already researched and stop asking Dashboard selection questions. The following are design decisions, before implementation or usability verification.

## Flows confirmed in official sources

- [Segment Source Debugger](https://www.twilio.com/docs/segment/connections/sources/debugger): verify integration by checking events arriving at a source, searching names/content, and viewing individual events in pretty/raw form. Its stream is sampled and is not a complete stored history of all events.
- [Amplitude Event Explorer](https://amplitude.com/docs/analytics/charts/event-explorer): find a user/device, run the app flow, and inspect events/properties. Batched events appear after Amplitude receives them.
- [Firebase DebugView](https://firebase.google.com/docs/analytics/debugview): consulted as a debug-event observation tool for SDK instrumentation checks. It does not justify adding Firebase-specific debug mode or further automatic collection to SignalDock.
- The web tool could not retrieve the Mixpanel Events documentation body in this research pass. Keep code evidence from installation ID research distinct from Dashboard UX evidence, and do not cite current Mixpanel UI details as confirmed facts.

Checked on 2026-10-08. Values such as 50 events and 30 days below are SignalDock decisions, not copied common standards from these vendors.

## First-release defaults

1. The initial screen shows events received today in the project timezone. Request [start,end) from the start of the current date to the start of the next date. The 'Today' preset follows the new date when the date changes. A fixed custom range does not move automatically.
2. Provide Today/Last 7 days/Last 30 days and a custom date range. The last N days means N calendar dates including today. Limit a single query to 30 calendar dates, while allowing the range to move to any past date. This is not a server retention TTL. Validate the limit in the API too; do not silently truncate ranges.
3. Use a newest-first receivedAt/eventId cursor for the list, with a default of 50 events and a maximum of 100. The UI uses 50. This matches the 50-event performance fixture, but the current user delegation also explicitly establishes it as a product default.
4. Limit search to exact-value filters for event name, installationId, and eventId. Do not add arbitrary properties search, user profiles, or funnels. Support copying individual record IDs without automatically adding a new public identity API to the SDK.
5. Details show name, ID, installationId, occurredAt, receivedAt, sdkVersion at collection, and properties. Provide a structured view and copyable stored-event JSON. Values have passed through JSONB, so do not call this a raw payload that preserves the original HTTP bytes or key order.
6. Keep counts by type and time as supporting receipt information. Default buckets are hour for a one-day range and day for longer ranges. The API accepts explicit hour/day with a maximum of 720 buckets. The existing performance fixture uses explicit day. Fill empty buckets within the range with 0.
7. Apply the same range and filters to lists and aggregates, but expose timing differences between separate responses. Query stored rows without sampling. The first list page is not the full event list and is distinct from server ACK counts.
8. Keep existing 5-second polling, no automatic navigation from past pages, Q108 contract-error stopping, and Q109 backoff. Distinguish list/aggregate last-success times from query-failure state.
9. Show an empty result as 'No events received during this period'. Suggest checking dates/filters and generating events in the demo. Do not infer SDK queue/network state as the reason for missing server events.
10. Show detail not-found as 'Event not found'. For temporary failures, show the error and a retry action separately. Do not reset user filters without authorization. Refresh and tab return must follow existing backoff.

## Evaluation flow

Product view/cart/simulated purchase in the app → check track's local-storage result → check queue/ACK in SDK diagnostics → inspect the Dashboard's latest received list and properties. Verify recovery after offline use and same-eventId resends through existing test results/unique DB rows. Do not infer SDK internal state or resend counts from the Dashboard alone.

Do not prioritize chart/design refinement over SDK work. Do not remove the existing performance-verification scope or agreed correctness conditions without a new user request.
