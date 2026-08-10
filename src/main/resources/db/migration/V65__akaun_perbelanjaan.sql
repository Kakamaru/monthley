-- Akaun GL untuk modul Perbelanjaan (ADR 0017).
--
-- ChartOfAccountSeeder kini menyemai akaun ini untuk SP BAHARU, tetapi ia
-- idempotent — ia melangkau SP yang sudah mempunyai carta akaun. Tanpa
-- migrasi ini, modul Perbelanjaan tidak berfungsi untuk SP sedia ada dan
-- kegagalannya berlaku ketika posting, bukan ketika modul diaktifkan.
--
-- GL diletak pada kategori INDUK, bukan jenis. Untung Rugi menunjukkan
-- tiga baris perbelanjaan (Utiliti, Penyelenggaraan, Pentadbiran), bukan
-- berpuluh. Pecahan sehingga 'Elektrik (TNB)' vs 'Air (IWK)' datang dari
-- laporan perbelanjaan yang membaca kategori, bukan dari lejar.
--
-- 5900 Perbelanjaan Am ialah lalai bila kategori tiada GL ditetapkan —
-- sama corak dengan defaultIncomeGlCode untuk produk. Tanpanya, kategori
-- baharu yang belum dipetakan memecahkan posting.

INSERT INTO chart_of_accounts
  (sp_code, code, name, account_type, normal_side, is_control, status,
   created_at, updated_at, version)
SELECT sp.sp_code, x.code, x.name, x.acc_type, x.side, 0, 'ACTIVE',
       NOW(), NOW(), 0
FROM   service_provider sp
CROSS  JOIN (
    SELECT '2000' AS code, 'Akaun Belum Bayar'           AS name, 'LIABILITY' AS acc_type, 'CREDIT' AS side
    UNION ALL SELECT '5100', 'Utiliti',                       'EXPENSE', 'DEBIT'
    UNION ALL SELECT '5200', 'Penyelenggaraan & Pembaikan',   'EXPENSE', 'DEBIT'
    UNION ALL SELECT '5300', 'Pentadbiran',                   'EXPENSE', 'DEBIT'
    UNION ALL SELECT '5900', 'Perbelanjaan Am',               'EXPENSE', 'DEBIT'
) x
WHERE NOT EXISTS (
    SELECT 1 FROM chart_of_accounts c
    WHERE c.sp_code = sp.sp_code AND c.code = x.code
);
