CREATE TABLE IF NOT EXISTS demo_sync_subject (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_sync_device (
 device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 UNIQUE KEY token_unique(token_sha256),
 FOREIGN KEY(subject_id) REFERENCES demo_sync_subject(subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_sync_payment (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receipt_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_date DATE NOT NULL,
 amount_cents BIGINT UNSIGNED NOT NULL,
 rule_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 deadline_date DATE NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 received_at DATETIME(6) NOT NULL,
 PRIMARY KEY(subject_id,event_id),
 UNIQUE KEY receipt_once(subject_id,receipt_id),
 FOREIGN KEY(subject_id) REFERENCES demo_sync_subject(subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
