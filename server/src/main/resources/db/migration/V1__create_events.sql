CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    installation_id UUID NOT NULL,
    name TEXT COLLATE "C" NOT NULL,
    occurred_at TIMESTAMPTZ(3) NOT NULL,
    sdk_version TEXT COLLATE "C" NOT NULL,
    properties JSONB NOT NULL,
    received_at TIMESTAMPTZ(3) NOT NULL DEFAULT date_trunc('milliseconds', clock_timestamp()),
    CONSTRAINT events_name_not_empty CHECK (name <> ''),
    CONSTRAINT events_properties_object CHECK (jsonb_typeof(properties) = 'object')
);

CREATE INDEX events_received_cursor_idx ON events (received_at DESC, event_id DESC);
