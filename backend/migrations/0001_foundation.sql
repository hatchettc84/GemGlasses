CREATE TABLE tenants (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL
);
INSERT INTO tenants (id, name, created_at)
VALUES ('t_default', 'Default', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
