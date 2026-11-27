CREATE TABLE stickies (
 tenant_id TEXT NOT NULL REFERENCES tenants(id), user_id TEXT NOT NULL REFERENCES users(id), id TEXT NOT NULL,
 device_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('note','draft')), title TEXT NOT NULL, text TEXT NOT NULL,
 job_title TEXT, created_at TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0 CHECK(done IN (0,1)), updated_at TEXT NOT NULL,
 PRIMARY KEY (tenant_id, user_id, id)
);
CREATE INDEX stickies_tenant ON stickies(tenant_id, created_at);
