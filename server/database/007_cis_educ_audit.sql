CREATE TABLE IF NOT EXISTS cis_educ_dispatch_audit (
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_type ENUM('PREPARED','RESERVED','UNCERTAIN','VERIFIED','RESTORED_QUARANTINE') NOT NULL,
 actor VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_at DATETIME(6) NOT NULL,
 PRIMARY KEY(message_id,event_type),
 FOREIGN KEY(message_id) REFERENCES cis_educ_dispatch(message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
