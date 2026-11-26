CREATE TABLE jobs (
 id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL REFERENCES tenants(id),
 title TEXT NOT NULL, site TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
 assigned_user_id TEXT REFERENCES users(id), status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','in_progress','completed')),
 due_at TEXT, revision INTEGER NOT NULL DEFAULT 1, created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX jobs_tenant ON jobs(tenant_id, updated_at);
CREATE TABLE tasks (
 id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL REFERENCES tenants(id), job_id TEXT NOT NULL REFERENCES jobs(id),
 title TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)), revision INTEGER NOT NULL DEFAULT 1,
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX tasks_job ON tasks(tenant_id,job_id);
CREATE TABLE documents (
 id TEXT PRIMARY KEY, tenant_id TEXT NOT NULL REFERENCES tenants(id), title TEXT NOT NULL, revision TEXT NOT NULL,
 models_json TEXT NOT NULL, pages_json TEXT NOT NULL, filename TEXT NOT NULL, content_type TEXT NOT NULL,
 size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, storage_key TEXT NOT NULL UNIQUE,
 status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','approved','revoked')),
 version INTEGER NOT NULL DEFAULT 1, created_by TEXT NOT NULL REFERENCES users(id), reviewed_by TEXT REFERENCES users(id),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX documents_tenant ON documents(tenant_id,updated_at);
