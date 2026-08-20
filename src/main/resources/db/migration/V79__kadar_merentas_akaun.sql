-- Kadar ketiga: bayaran merentas beberapa AKAUN.
--
-- rate_single  satu invois, satu akaun
-- rate_multi   beberapa invois, satu akaun
-- rate_multi_acct  beberapa akaun (ADR 0019)
--
-- Kadar mencerminkan kerja pada gerbang. Bayaran merentas akaun
-- menghasilkan beberapa resit daripada satu transaksi, jadi ia lebih kerja
-- daripada beberapa invois pada satu akaun.
--
-- Lalai 2.50 mengikut rate_multi 2.00 — cukup untuk pemasangan berfungsi,
-- dan SP boleh melaraskannya melalui skrin tetapan gerbang.

ALTER TABLE sp_payment_setting
  ADD COLUMN rate_multi_acct DECIMAL(15,2) NULL AFTER rate_multi;

-- SP sedia ada mendapat lalai; NULL bermakna tiada caj, dan pemasangan
-- yang terlepas tetapan tidak sepatutnya senyap-senyap percuma.
UPDATE sp_payment_setting
SET    rate_multi_acct = 2.50
WHERE  rate_multi_acct IS NULL;


-- ---------------------------------------------------------------------------
-- Baris transaksi membawa AKAUNNYA (ADR 0019)
-- ---------------------------------------------------------------------------
--
-- Bayaran merentas akaun menghasilkan beberapa resit daripada satu
-- transaksi gerbang, dan callback perlu tahu invois mana milik akaun mana
-- untuk memecahkannya. Tanpa lajur ini, pecahan memerlukan pertanyaan
-- kembali ke financial_document — dan invois yang dipindahkan antara akaun
-- selepas bayaran akan memecahkan pengiraan itu.
--
-- Menyimpan account_id pada masa bayaran bermakna pecahan mencerminkan
-- keadaan SEMASA bayaran, bukan keadaan semasa callback diproses.

ALTER TABLE gateway_txn_line
  ADD COLUMN account_id BIGINT NULL AFTER txn_id;

-- Baris sedia ada diisi daripada dokumennya. Semuanya bayaran satu akaun,
-- jadi tiada kekaburan.
UPDATE gateway_txn_line l
JOIN   financial_document d ON d.id = l.document_id
SET    l.account_id = d.account_id
WHERE  l.account_id IS NULL;

ALTER TABLE gateway_txn_line
  ADD INDEX idx_gtl_account (account_id);
