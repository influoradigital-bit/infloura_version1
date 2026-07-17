-- Task #9 (DealController + DealMessage timeline) — unified per-collaboration event stream.
-- Mirrors TimelineEvent / DealMessage in src/lib/api.ts and types.ts.

CREATE TABLE deal_messages (
  id                VARCHAR(26) PRIMARY KEY,
  collaboration_id  VARCHAR(26) NOT NULL,
  kind              ENUM('text','system','proposal','contract','deliverable','payment','shipment') NOT NULL DEFAULT 'text',
  sender_id         VARCHAR(26) NOT NULL,
  sender_type       ENUM('brand','creator','system') NOT NULL,
  content           TEXT,
  metadata          JSON,
  read_by_json      JSON,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_deal_msg_collab_time (collaboration_id, created_at),
  CONSTRAINT fk_deal_msg_collab FOREIGN KEY (collaboration_id) REFERENCES collaborations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
