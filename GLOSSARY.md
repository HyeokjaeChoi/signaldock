# SignalDock

Terms used to collect and send business events from shopping scenarios and inspect their processing results.

## Language

**Business event**:
A record of an action or result in a client app. The client app defines the event name and properties. In the demo app, events represent product views, additions to the cart, and completed simulated purchases.

**Event properties**:
Named values that describe a business event and the circumstances in which it occurred. A product ID on a product-view event is one example.

**Demo app**:
A shopping sample app that integrates the SignalDock SDK and demonstrates product views, additions to the cart, and simulated purchases.
_Avoid_: shopping service

**Installation ID**:
An identifier for one installation of a client app. Multiple events from the same installation share it.
_Avoid_: user ID, event ID

**Event ID**:
An identifier for one business event. It stays the same when the event is sent again and lets the server detect duplicates.
_Avoid_: installation ID

**Simulated purchase**:
A purchase flow in the demo app that reproduces success or failure without an actual payment.
_Avoid_: real payment, payment completed

**Collection success**:
A collection result that confirms the SDK has finished storing an event. It is distinct from server receipt and completed server processing.
_Avoid_: delivery success, server processing completed

**Receipt success**:
Confirmation that the server has accepted an event and taken responsibility for subsequent processing. It is distinct from SDK collection success and completed server aggregation.
_Avoid_: aggregation completed
