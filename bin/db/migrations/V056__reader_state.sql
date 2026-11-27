-- Reader state: saves, follows and reading history for signed-in readers
-- (docs/superpowers/specs/2026-11-26-reader-and-admin-experience-design.md,
-- Part E). Every row goes with its account, entry or weblog (ON DELETE
-- CASCADE), so deleting any of them leaves no orphan. No JPA entity maps
-- these tables: business.reader reads and writes them in native SQL, so the
-- shared cache never holds a row the cascade removed. Idempotent.

CREATE TABLE IF NOT EXISTS pressroom_reader_save (
    user_id  varchar(48) NOT NULL
             CONSTRAINT prs_user_fk REFERENCES pressroom_user(id) ON DELETE CASCADE,
    entry_id varchar(48) NOT NULL
             CONSTRAINT prs_entry_fk REFERENCES weblogentry(id) ON DELETE CASCADE,
    created  timestamp(3) with time zone NOT NULL,
    CONSTRAINT prs_pk PRIMARY KEY (user_id, entry_id)
);
CREATE INDEX IF NOT EXISTS prs_entry_idx ON pressroom_reader_save (entry_id);

CREATE TABLE IF NOT EXISTS pressroom_reader_follow (
    user_id   varchar(48) NOT NULL
              CONSTRAINT prf_user_fk REFERENCES pressroom_user(id) ON DELETE CASCADE,
    weblog_id varchar(48) NOT NULL
              CONSTRAINT prf_weblog_fk REFERENCES weblog(id) ON DELETE CASCADE,
    created   timestamp(3) with time zone NOT NULL,
    CONSTRAINT prf_pk PRIMARY KEY (user_id, weblog_id)
);
CREATE INDEX IF NOT EXISTS prf_weblog_idx ON pressroom_reader_follow (weblog_id);

-- Upserted on every read; trimmed to the newest 500 per reader as it is written.
CREATE TABLE IF NOT EXISTS pressroom_reader_read (
    user_id  varchar(48) NOT NULL
             CONSTRAINT prr_user_fk REFERENCES pressroom_user(id) ON DELETE CASCADE,
    entry_id varchar(48) NOT NULL
             CONSTRAINT prr_entry_fk REFERENCES weblogentry(id) ON DELETE CASCADE,
    read_at  timestamp(3) with time zone NOT NULL,
    CONSTRAINT prr_pk PRIMARY KEY (user_id, entry_id)
);
CREATE INDEX IF NOT EXISTS prr_user_read_idx ON pressroom_reader_read (user_id, read_at DESC);
CREATE INDEX IF NOT EXISTS prr_entry_idx ON pressroom_reader_read (entry_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON pressroom_reader_save TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON pressroom_reader_follow TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON pressroom_reader_read TO :app_user;
