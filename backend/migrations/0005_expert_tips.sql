-- Expert tips from senior technicians. A tip is a normal documents row (kind='tip', status 'draft' until the office approves it).
ALTER TABLE users ADD COLUMN senior INTEGER NOT NULL DEFAULT 0 CHECK (senior IN (0,1));
ALTER TABLE documents ADD COLUMN kind TEXT NOT NULL DEFAULT 'manual' CHECK (kind IN ('manual','tip'));
