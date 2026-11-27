-- Mirror of stickies into Plane (Part 1A). plane_state: pending | synced | delete_pending.
-- deleted=1 rows are tombstones kept until Plane confirms the delete; they are hidden everywhere.
-- plane_claimed_at: lease taken by a running mirror so two mirrors never work the same row at once.
ALTER TABLE stickies ADD COLUMN job_id TEXT;
ALTER TABLE stickies ADD COLUMN plane_project_id TEXT;
ALTER TABLE stickies ADD COLUMN plane_item_id TEXT;
ALTER TABLE stickies ADD COLUMN plane_comment_id TEXT;
ALTER TABLE stickies ADD COLUMN plane_state TEXT NOT NULL DEFAULT 'pending' CHECK(plane_state IN ('pending','synced','delete_pending'));
ALTER TABLE stickies ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0 CHECK(deleted IN (0,1));
ALTER TABLE stickies ADD COLUMN plane_claimed_at TEXT;
CREATE INDEX stickies_plane_retry ON stickies(tenant_id, user_id, plane_state, updated_at);
