-- SDK platform (ADR-0046). Rows stored before this migration come from Android SDK 0.1.0 to 0.3.0,
-- which send no platform, so they become 'android'. The default only fills old rows: it is dropped,
-- so ingestion must set the value (the missing-field default lives in the ingest rules, not here).
ALTER TABLE events
    ADD COLUMN sdk_platform TEXT COLLATE "C" NOT NULL DEFAULT 'android',
    ADD CONSTRAINT events_sdk_platform_known CHECK (sdk_platform IN ('android', 'ios'));
ALTER TABLE events ALTER COLUMN sdk_platform DROP DEFAULT;

ALTER TABLE client_reports
    ADD COLUMN sdk_platform TEXT COLLATE "C" NOT NULL DEFAULT 'android',
    ADD CONSTRAINT client_reports_sdk_platform_known CHECK (sdk_platform IN ('android', 'ios'));
ALTER TABLE client_reports ALTER COLUMN sdk_platform DROP DEFAULT;
