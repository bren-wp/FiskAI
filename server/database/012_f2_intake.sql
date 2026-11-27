CREATE TABLE IF NOT EXISTS f2_kpd_release (
 revision VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 catalog_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 imported_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS f2_kpd_code (
 revision VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 code CHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name_hr TEXT NOT NULL,
 PRIMARY KEY(revision,code),
 FOREIGN KEY(revision) REFERENCES f2_kpd_release(revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS f2_educ_intake (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 direction ENUM('ISSUED','RECEIVED') NOT NULL,
 identity_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 review_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sealed_evidence MEDIUMBLOB NOT NULL,
 state ENUM('REVIEW_REQUIRED') NOT NULL DEFAULT 'REVIEW_REQUIRED',
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(subject_id,document_id),
 UNIQUE KEY document_identity(subject_id,direction,identity_sha256),
 FOREIGN KEY(subject_id) REFERENCES educ_business_subject(subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
