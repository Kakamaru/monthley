-- Invois adhoc — invois kepada orang yang BUKAN pelanggan berdaftar.
--
-- Contoh sebenar: JMB mengenakan caj clamp kepada pemandu luar yang
-- meletak kereta dalam kawasan. Orang itu tiada akaun dan tidak
-- sepatutnya mempunyai satu.
--
-- financial_document.account_id sudah nullable, dan setiap VIEW menapis
-- 'account_id IS NOT NULL' — skema memang menjangka dokumen tanpa akaun.
-- Yang menghalang ialah fi_allocation.account_id NOT NULL: invois boleh
-- wujud tanpa akaun, tetapi BAYARANNYA tidak.
--
-- Lajur itu turunan sepenuhnya. Tiada VIEW menggunakannya, dan setiap
-- query mencapai alokasi melalui debit_document_id atau
-- credit_document_id. Disahkan sebelum perubahan ini.
--
-- idem_key TIDAK perlu diubah. Ia lajur dijana daripada
-- CONCAT(account_id, ...), dan CONCAT dengan mana-mana argumen NULL
-- memberi NULL. UNIQUE membenarkan berbilang NULL, jadi dua caj clamp
-- kepada dua orang berbeza dalam tempoh yang sama tidak berlanggar.
-- Disahkan dengan dua INSERT identik dalam transaksi yang digulung.
ALTER TABLE fi_allocation
  MODIFY COLUMN account_id BIGINT NULL;

-- account_allocation_match mengambil account_id daripada ALOKASI, jadi
-- padanan adhoc akan memaparkan NULL. Ambil daripada dokumen debit
-- sebaliknya — hubungannya menjadi eksplisit, dan invois berakaun kekal
-- sama kerana kedua-duanya sentiasa sepadan.
CREATE OR REPLACE VIEW account_allocation_match AS
SELECT a.sp_code                AS sp_code,
       dd.account_id            AS account_id,
       a.credit_document_id     AS credit_document_id,
       a.debit_document_id      AS debit_document_id,
       a.debit_document_line_id AS debit_document_line_id,
       cd.doc_no                AS credit_doc_no,
       cd.doc_date              AS credit_doc_date,
       dd.doc_no                AS debit_doc_no,
       dd.doc_date              AS debit_doc_date,
       COALESCE(lp.name_, fp.name_, '') AS debit_period,
       -- Sandaran ialah fi_period, bukan dokumen: financial_document
       -- tiada period_start/period_end. Percubaan pertama menulisnya
       -- daripada andaian dan migrasi gagal separuh.
       COALESCE(l.period_start, fp.start_dt) AS debit_period_start,
       COALESCE(l.period_end, fp.end_dt)     AS debit_period_end,
       p.name                   AS product_name,
       l.description            AS line_description,
       dd.title                 AS debit_title,
       a.amount                 AS amount
FROM       fi_allocation a
JOIN       financial_document cd ON cd.id = a.credit_document_id
JOIN       financial_document dd ON dd.id = a.debit_document_id
LEFT JOIN  financial_document_line l
           ON l.id = a.debit_document_line_id AND l.active = 1
LEFT JOIN  product p    ON p.id = l.product_id
LEFT JOIN  fi_period lp ON lp.period_id = l.period_id
LEFT JOIN  fi_period fp ON fp.period_id = dd.period_id
WHERE a.status = 'ACTIVE';
