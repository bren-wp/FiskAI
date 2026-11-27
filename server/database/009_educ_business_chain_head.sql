CREATE TABLE IF NOT EXISTS educ_business_chain_head (
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 chain_position INT UNSIGNED NOT NULL,
 entry_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 head_mac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(subject_id) REFERENCES educ_business_subject(subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
