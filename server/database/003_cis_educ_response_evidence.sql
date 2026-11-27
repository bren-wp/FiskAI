CREATE TABLE IF NOT EXISTS cis_educ_response_evidence (
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 trust_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 jir CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 sealed_response MEDIUMBLOB NULL,
 registered_at DATETIME(6) NOT NULL,
 verified_at DATETIME(6) NULL,
 UNIQUE KEY unique_educ_jir(jir),
 UNIQUE KEY unique_educ_response(response_sha256),
 KEY subject_document(subject_id,document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
