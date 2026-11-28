CREATE TABLE IF NOT EXISTS legal_decision_ledger (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 review_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 decision_json BLOB NOT NULL,
 decision_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(subject_id,document_id),
 FOREIGN KEY(subject_id,document_id) REFERENCES f2_educ_intake(subject_id,document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
