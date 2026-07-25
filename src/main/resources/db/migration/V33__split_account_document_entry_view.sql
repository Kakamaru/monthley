-- ADR 0010 P2 — pecahkan account_balance kepada dua lapisan.
--
-- V32 menggabungkan DUA keputusan dalam satu VIEW: apa TANDA setiap dokumen,
-- dan bagaimana ia DIJUMLAHKAN. Penyata memerlukan yang pertama tanpa yang
-- kedua — ia perlu setiap dokumen sebagai baris berasingan dengan baki
-- berjalan, bukan satu jumlah per akaun.
--
-- Jika penyata menulis semula CASE WHEN doc_type IN (...) dalam querynya
-- sendiri, kita mencipta takrifan TANDA kedua. Itu guard 6 pecah pada hari
-- pertama. Maka: satu VIEW mentakrif tanda, satu lagi menjumlahkannya.
--
-- account_balance kekal wujud dengan nama dan makna yang sama; ia kini
-- diterbitkan daripada lapisan di bawahnya. Nilainya tidak berubah.

-- Lapisan 1 — TANDA. Satu baris per dokumen.
--
-- signed_amount ialah SATU-SATUNYA tempat yang memutuskan sama ada sesuatu
-- dokumen menambah atau menolak baki, dan bahawa cukai termasuk. Pemanggil
-- menjumlahkannya; mereka tidak pernah memeriksa doc_type sendiri.
--
-- Dokumen CANCELLED mempunyai signed_amount = 0, bukan dibuang. Ini
-- membolehkan penyata MEMAPARKAN dokumen batal (legend aktif/batal) tanpa
-- ia menggerakkan lajur baki.
--
-- ELSE 0 ialah risiko yang diketahui: doc_type baharu yang tidak disenaraikan
-- akan hilang daripada baki secara SENYAP. Dikunci oleh
-- AccountDocumentEntryViewTest, bukan oleh harapan.
CREATE OR REPLACE VIEW account_document_entry AS
SELECT d.account_id     AS account_id,
       d.sp_code        AS sp_code,
       d.id             AS document_id,
       d.doc_no         AS doc_no,
       d.doc_type       AS doc_type,
       d.doc_date       AS doc_date,
       d.due_date       AS due_date,
       d.status         AS status,
       d.title          AS title,
       d.ref_no         AS ref_no,
       d.cancel_reason  AS cancel_reason,
       d.amount         AS amount,
       d.tax_amount     AS tax_amount,
       CASE WHEN d.status = 'CANCELLED'                  THEN 0
            WHEN d.doc_type IN ('INVOICE','DEBIT_NOTE')  THEN  (d.amount + d.tax_amount)
            WHEN d.doc_type IN ('RECEIPT','CREDIT_NOTE') THEN -(d.amount + d.tax_amount)
            ELSE 0
       END              AS signed_amount
FROM financial_document d
WHERE d.account_id IS NOT NULL;

-- Lapisan 2 — JUMLAH. Takrifan baki tidak berubah (ADR 0009); ia kini
-- diterbitkan daripada lapisan tanda dan bukan mengulanginya.
--
-- Baki boleh NEGATIF (pelanggan ada kredit). Alokasi TIDAK terlibat.
--
-- NOTA: akaun yang HANYA mempunyai dokumen batal kini muncul dengan baki 0,
-- sedangkan sebelum ini ia tidak muncul langsung. Akaun tanpa sebarang
-- dokumen masih tidak muncul. Pemanggil tetap mesti guna
-- LEFT JOIN + COALESCE(ab.balance, 0).
CREATE OR REPLACE VIEW account_balance AS
SELECT e.account_id                      AS account_id,
       COALESCE(SUM(e.signed_amount), 0) AS balance
FROM   account_document_entry e
GROUP  BY e.account_id;
