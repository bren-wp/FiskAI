CREATE TABLE IF NOT EXISTS educ_business_subject (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_oib CHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 UNIQUE KEY owner_once(owner_oib)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS educ_business_document (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 chain_position INT UNSIGNED NOT NULL,
 fiscal_year SMALLINT UNSIGNED NOT NULL,
 premise VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 register_no VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 invoice_no VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 previous_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 entry_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sealed_document MEDIUMBLOB NOT NULL,
 PRIMARY KEY(subject_id,document_id),
 UNIQUE KEY unique_message(message_id),
 UNIQUE KEY chain_position(subject_id,chain_position),
 UNIQUE KEY invoice_identity(subject_id,fiscal_year,premise,register_no,invoice_no),
 FOREIGN KEY(subject_id) REFERENCES educ_business_subject(subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
