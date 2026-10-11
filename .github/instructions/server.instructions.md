---
applyTo: "server/**"
---

# Server receipt review rules

- Enforce eventId uniqueness with a DB constraint, not lookup-then-insert. Insert valid items in a transaction and return item-level results only after commit.
- Times are UTC RFC 3339 with millisecond precision. Accept numeric offsets as the same instant; reject local times without offsets.
