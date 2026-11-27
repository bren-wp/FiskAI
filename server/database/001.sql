CREATE TABLE IF NOT EXISTS price_lists (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 site_key VARCHAR(64) CHARACTER SET ascii NOT NULL,
 kind ENUM('ROBA','USLUGE') NOT NULL,
 version_no BIGINT UNSIGNED NOT NULL,
 filename VARCHAR(255) NOT NULL,
 published_at DATETIME(6) NOT NULL,
 replaced_at DATETIME(6) NULL,
 csv_content MEDIUMBLOB NOT NULL,
 xml_content MEDIUMBLOB NOT NULL,
 UNIQUE KEY version_unique(site_key,kind,version_no),
 KEY archive_lookup(site_key,kind,replaced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS price_heads (
 site_key VARCHAR(64) CHARACTER SET ascii NOT NULL,
 kind ENUM('ROBA','USLUGE') NOT NULL,
 current_id BIGINT UNSIGNED NULL,
 last_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(site_key,kind),
 FOREIGN KEY(current_id) REFERENCES price_lists(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS kpd_catalog (
 code VARCHAR(16) CHARACTER SET ascii NOT NULL,
 valid_from DATE NOT NULL,
 valid_to DATE NULL,
 name_hr VARCHAR(1000) NOT NULL,
 PRIMARY KEY(code,valid_from),
 KEY valid_to_idx(valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS kpd_source (
 id TINYINT PRIMARY KEY,
 source_url VARCHAR(1000) NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
 imported_at DATETIME NOT NULL,
 record_count INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
