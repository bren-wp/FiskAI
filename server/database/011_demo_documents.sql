CREATE TABLE IF NOT EXISTS demo_document_scope (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_oib CHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(subject_id) REFERENCES demo_sync_subject(subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_sync_document (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fiscal_identity VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sealed_document MEDIUMBLOB NOT NULL,
 received_at DATETIME(6) NOT NULL,
 PRIMARY KEY(subject_id,document_id),
 UNIQUE KEY document_number(subject_id,fiscal_identity),
 FOREIGN KEY(subject_id) REFERENCES demo_document_scope(subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
