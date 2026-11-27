CREATE TABLE IF NOT EXISTS cis_educ_dispatch (
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 signer_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sealed_request MEDIUMBLOB NOT NULL,
 state ENUM('PREPARED','RESERVED','UNCERTAIN','VERIFIED') NOT NULL,
 reservation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 reserved_at DATETIME(6) NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY one_document(subject_id,document_id),
 FOREIGN KEY(message_id) REFERENCES cis_educ_response_evidence(message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
