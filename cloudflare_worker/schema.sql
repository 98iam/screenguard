-- ScreenGuard Cloudflare D1 Database Schema

CREATE TABLE IF NOT EXISTS incidents (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    package_name TEXT NOT NULL,
    app_name TEXT NOT NULL,
    matched_word TEXT NOT NULL,
    screenshot_key TEXT,
    timestamp INTEGER NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_incidents_timestamp ON incidents(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_incidents_device ON incidents(device_id);

CREATE TABLE IF NOT EXISTS pause_requests (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    duration_minutes INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
    requested_at INTEGER NOT NULL,
    approved_until INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pause_requests_device ON pause_requests(device_id, status);
