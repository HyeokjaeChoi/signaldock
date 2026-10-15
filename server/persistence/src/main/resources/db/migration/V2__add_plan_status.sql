-- Tracking plan status (ADR-0022). NULL means "not checked": no plan was configured at ingest,
-- or the row was stored before this migration. Existing rows stay NULL; there is no backfill.
ALTER TABLE events
    ADD COLUMN plan_status TEXT COLLATE "C",
    ADD CONSTRAINT events_plan_status_known CHECK (plan_status IN ('planned', 'unplanned_event', 'invalid_properties'));
