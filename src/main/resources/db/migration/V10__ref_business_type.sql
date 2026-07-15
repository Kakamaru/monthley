-- =====================================================================
-- V10 — Jenis perniagaan SP (dari ref_biz_type lama)
-- =====================================================================

CREATE TABLE IF NOT EXISTS ref_business_type (
  code        VARCHAR(10)  NOT NULL,
  name        VARCHAR(100) NOT NULL,
  description VARCHAR(255) NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  status      ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  version     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO ref_business_type (code, name, description, sort_order) VALUES
  ('CLHO', 'Clubhouse',                'Kelab & kemudahan rekreasi',                 1),
  ('EDU',  'Education',                'Sekolah, tadika, tahfiz, pusat tuisyen',     2),
  ('IT',   'Information Technology',   'Perkhidmatan teknologi maklumat',            3),
  ('JMB',  'Joint Management Board',   'High Rise Apartment — JMB',                  4),
  ('JMC',  'Joint Management Committee','High Rise Apartment — JMC',                 5),
  ('MFG',  'Manufacturing',            'Pembuatan & perkilangan',                    6),
  ('SUBL', 'Sublet',                   'Sewaan & sublet unit',                       7);

-- Pautkan service_provider.business_type ke rujukan
ALTER TABLE service_provider
  ADD CONSTRAINT fk_sp_biz_type FOREIGN KEY (business_type) REFERENCES ref_business_type (code);
