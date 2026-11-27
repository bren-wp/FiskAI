CREATE TABLE IF NOT EXISTS demo_sync_device_proof (
 device_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 public_key TEXT CHARACTER SET ascii NOT NULL,
 key_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 nonce CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 expires_at DATETIME(6) NULL,
 FOREIGN KEY(device_id) REFERENCES demo_sync_device(device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
