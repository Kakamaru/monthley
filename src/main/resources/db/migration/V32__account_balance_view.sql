-- Satu takrifan baki akaun (ADR 0009).
--
-- Baki = SUM(INVOICE + DEBIT_NOTE) - SUM(RECEIPT + CREDIT_NOTE)
--
-- Alokasi TIDAK terlibat. Alokasi ialah untuk padanan (resit mana membayar
-- invois mana), bukan untuk jumlah baki. Ini menjadikan baki teguh: alokasi
-- yang tersilap atau terbalik tidak boleh merosakkannya — kegagalan CASE-001.
--
-- Baki boleh NEGATIF. Negatif bermakna pelanggan ada kredit (advance atau
-- kredit nota melebihi invois), bukan ralat.
--
-- Sebagai VIEW supaya semua query berkongsi SATU takrifan. Sebelum ini empat
-- tempat mengira baki dengan formula berbeza dan tidak bersetuju
-- (cara-kerja.md guard 6).
--
-- NOTA: akaun tanpa sebarang dokumen TIDAK muncul dalam view ini.
-- Pemanggil mesti guna LEFT JOIN + COALESCE(ab.balance, 0).

CREATE OR REPLACE VIEW account_balance AS
SELECT d.account_id AS account_id,
       COALESCE(SUM(CASE WHEN d.doc_type IN ('INVOICE','DEBIT_NOTE')
                         THEN d.amount + d.tax_amount ELSE 0 END), 0)
     - COALESCE(SUM(CASE WHEN d.doc_type IN ('RECEIPT','CREDIT_NOTE')
                         THEN d.amount + d.tax_amount ELSE 0 END), 0) AS balance
FROM financial_document d
WHERE d.status <> 'CANCELLED'
  AND d.account_id IS NOT NULL
GROUP BY d.account_id;
