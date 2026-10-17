-- SDK client reports (ADR-0036): events the SDK dropped, by SDK version and reason. One row per
-- report entry. No installation ID or other identity is stored (ADR-0001). A report is not
-- idempotent, so the counts are approximate. Queries sum the rows; there are no counters (Q70).
CREATE TABLE client_reports (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_at TIMESTAMPTZ(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    sdk_version TEXT COLLATE "C" NOT NULL,
    reason TEXT COLLATE "C" NOT NULL,
    count INTEGER NOT NULL,
    CONSTRAINT client_reports_sdk_version_not_empty CHECK (sdk_version <> ''),
    CONSTRAINT client_reports_reason_known CHECK (reason IN ('queue_full', 'storage_error', 'invalid_event', 'event_too_large', 'server_rejected')),
    CONSTRAINT client_reports_count_range CHECK (count BETWEEN 1 AND 1000000)
);

CREATE INDEX client_reports_received_idx ON client_reports (received_at);
