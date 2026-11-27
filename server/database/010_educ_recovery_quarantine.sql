CREATE TABLE IF NOT EXISTS educ_recovery_quarantine (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 restored_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
