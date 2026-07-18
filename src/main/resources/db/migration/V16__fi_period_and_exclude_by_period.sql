-- Tempoh kewangan — padan skema Monthley asal.
--   period_id = tahun*1000000 + H*100000 + Q*10000 + bulan*100
--   Nota: ikut data prod, QR parent-nya YR (bukan HF). HF tiada anak.
--   Jadual ini RUJUKAN sahaja — enjin bil guna PeriodIds (fungsi tulen).
--   Tiada CTE: kena jalan atas MySQL 9 (dev) DAN MariaDB 11 (prod).
CREATE TABLE fi_period (
  period_id   BIGINT      NOT NULL,
  name_       VARCHAR(50) NOT NULL,
  parent_id   BIGINT      NULL,
  charge_code VARCHAR(2)  NOT NULL COMMENT 'YR/HF/QR/MO',
  start_dt    DATE        NOT NULL,
  end_dt      DATE        NOT NULL,
  lang_key    VARCHAR(50) NULL,
  PRIMARY KEY (period_id),
  KEY idx_fi_period_charge (charge_code, period_id),
  KEY idx_fi_period_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TEMPORARY TABLE _yr (y INT PRIMARY KEY);
INSERT INTO _yr (y) VALUES
 (2017),(2018),(2019),(2020),(2021),(2022),(2023),(2024),(2025),(2026),
 (2027),(2028),(2029),(2030),(2031),(2032),(2033),(2034),(2035);

CREATE TEMPORARY TABLE _mo (m INT PRIMARY KEY);
INSERT INTO _mo (m) VALUES (1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12);

-- Tahun
INSERT INTO fi_period (period_id, name_, parent_id, charge_code, start_dt, end_dt)
SELECT y*1000000, CAST(y AS CHAR), NULL, 'YR',
       MAKEDATE(y,1), MAKEDATE(y+1,1) - INTERVAL 1 DAY
FROM _yr;

-- Separuh tahun
INSERT INTO fi_period (period_id, name_, parent_id, charge_code, start_dt, end_dt)
SELECT y*1000000 + h*100000, CONCAT('H',h,', ',y), y*1000000, 'HF',
       MAKEDATE(y,1) + INTERVAL (h-1)*6 MONTH,
       LAST_DAY(MAKEDATE(y,1) + INTERVAL (h*6-1) MONTH)
FROM _yr, (SELECT 1 AS h UNION ALL SELECT 2) hh;

-- Suku tahun
INSERT INTO fi_period (period_id, name_, parent_id, charge_code, start_dt, end_dt)
SELECT y*1000000 + IF(q<=2,1,2)*100000 + q*10000, CONCAT('Q',q,', ',y), y*1000000, 'QR',
       MAKEDATE(y,1) + INTERVAL (q-1)*3 MONTH,
       LAST_DAY(MAKEDATE(y,1) + INTERVAL (q*3-1) MONTH)
FROM _yr, (SELECT 1 AS q UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) qq;

-- Bulan
INSERT INTO fi_period (period_id, name_, parent_id, charge_code, start_dt, end_dt)
SELECT y*1000000 + IF(m<=6,1,2)*100000 + CEIL(m/3)*10000 + m*100,
       CONCAT(MONTHNAME(MAKEDATE(y,1) + INTERVAL (m-1) MONTH), ', ', y),
       y*1000000 + IF(m<=6,1,2)*100000 + CEIL(m/3)*10000, 'MO',
       MAKEDATE(y,1) + INTERVAL (m-1) MONTH,
       LAST_DAY(MAKEDATE(y,1) + INTERVAL (m-1) MONTH)
FROM _yr, _mo;

DROP TEMPORARY TABLE _yr;
DROP TEMPORARY TABLE _mo;

-- invoice_exclude_period: 'YYYY-MM' -> period_id
ALTER TABLE invoice_exclude_period ADD COLUMN period_id BIGINT NULL AFTER sp_code;

UPDATE invoice_exclude_period x
  JOIN fi_period p ON p.charge_code = 'MO'
   AND DATE_FORMAT(p.start_dt, '%Y-%m') = x.period
   SET x.period_id = p.period_id;

DELETE FROM invoice_exclude_period WHERE period_id IS NULL;

ALTER TABLE invoice_exclude_period
  DROP KEY uk_excl,
  DROP COLUMN period,
  MODIFY COLUMN period_id BIGINT NOT NULL,
  ADD UNIQUE KEY uk_excl (sp_code, period_id),
  ADD CONSTRAINT fk_excl_period FOREIGN KEY (period_id) REFERENCES fi_period (period_id);
