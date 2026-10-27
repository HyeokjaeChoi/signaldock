-- Queue schema, version 1 (ADR-0047 D7). Same tables and columns as the Android Room schema 3 (ADR-0003, ADR-0025, ADR-0036).
-- A released migration is frozen: a change needs a new V<n>__*.sql file (scripts/check-repo-rules.sh).
CREATE TABLE events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  eventId TEXT NOT NULL,
  installationId TEXT NOT NULL,
  contractVersion TEXT NOT NULL,
  payloadJson TEXT NOT NULL,
  sizeBytes INTEGER NOT NULL,
  createdAtMillis INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_events_eventId ON events (eventId);
CREATE TABLE rejections (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  eventId TEXT NOT NULL,
  code TEXT NOT NULL,
  rejectedAtMillis INTEGER NOT NULL
);
CREATE TABLE retry_state (
  id INTEGER NOT NULL,
  failureCount INTEGER NOT NULL,
  nextAttemptAtMillis INTEGER,
  waitMillis INTEGER,
  lastAckAtMillis INTEGER,
  lastAckAccepted INTEGER NOT NULL,
  lastAckDuplicate INTEGER NOT NULL,
  lastAckRejected INTEGER NOT NULL,
  lastErrorKind TEXT,
  lastErrorHttpStatus INTEGER,
  lastErrorAtMillis INTEGER,
  hold INTEGER NOT NULL,
  maxBatchEvents INTEGER,
  PRIMARY KEY (id)
);
CREATE TABLE dropped_counts (
  reason TEXT NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (reason)
);
