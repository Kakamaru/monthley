-- Catatan pada baris dokumen.
--
-- Caj penggunaan membawa catatan yang kerani taip dalam Excel —
-- 'bacaan meter 1213', 'lebih masa 3 jam'. Ia menerangkan MENGAPA
-- amaun itu, dan pelanggan yang membuka penyata memerlukannya.
--
-- Draf pertama menyimpannya sebagai `description`, menggantikan nama
-- produk. Itu berfungsi sehingga seseorang menyoal description dan
-- mendapati ia bermakna DUA perkara: nama produk untuk baris langganan,
-- catatan untuk baris penggunaan. Tiada apa dalam baris itu memberitahu
-- yang mana.
--
-- Lajur sendiri. description kekal nama produk; remarks menerangkannya.
-- Penyata memaparkan kedua-duanya, catatan sebagai baris kecil di bawah
-- — corak sama seperti sebab pembatalan (V53).

ALTER TABLE financial_document_line
  ADD COLUMN remarks VARCHAR(255) NULL AFTER description;

-- VIEW membawa catatan ke lapisan penyata.
--
-- ASAS: takrifan HIDUP (information_schema), disahkan sepadan dengan
-- V36 — tiada migrasi kemudian menyentuh VIEW ini. CREATE OR REPLACE
-- menulis ganti SEPENUHNYA; menyalin daripada fail lama tanpa menyemak
-- membuang setiap lajur yang ditambah selepasnya (cara-kerja 6b).
--
-- description kekal COALESCE(p.name, ...) — nama produk didahulukan,
-- dan itu sebabnya penyata sudah memaparkan 'WATER CHARGES' walaupun
-- baris menyimpan catatan sebagai description. Catatan kini mempunyai
-- lajurnya sendiri dan tidak perlu berebut tempat.

CREATE OR REPLACE VIEW account_document_line AS
SELECT d.sp_code                                   AS sp_code,
       d.account_id                                AS account_id,
       d.id                                        AS document_id,
       l.id                                        AS line_id,
       COALESCE(p.name, l.description, d.title)    AS description,
       l.remarks                                   AS remarks,
       COALESCE(l.period_start, lp.start_dt)       AS period_start,
       COALESCE(l.period_end,   lp.end_dt)         AS period_end,
       (l.amount + l.tax_amount)                   AS amount
FROM       financial_document_line l
JOIN       financial_document      d  ON d.id         = l.document_id
LEFT JOIN  product                 p  ON p.id         = l.product_id
LEFT JOIN  fi_period               lp ON lp.period_id = l.period_id
WHERE l.active = 1
  AND d.account_id IS NOT NULL;
