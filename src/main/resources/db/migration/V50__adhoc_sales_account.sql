-- Akaun SALES untuk invois adhoc — satu per SP.
--
-- Invois adhoc ialah invois kepada orang yang BUKAN pelanggan: caj clamp
-- kepada pemandu luar, jualan buku pada pameran sekolah. Mereka menerima
-- e-mel dengan pautan bayaran dan tidak akan kembali.
--
-- KENAPA AKAUN LANGSUNG. journal_line.sub_ledger_account_id mempunyai FK
-- ke account, jadi ia mesti akaun sebenar atau NULL. NULL memecahkan
-- rekonsiliasi: akaun kawalan AR bergerak sementara subsidiari pelanggan
-- tidak — Family 3 dalam accounting-invariants.md, melalui pintu lain.
--
-- KENAPA SATU DIKONGSI dan bukan satu setiap pembeli. Setiap baris dalam
-- jadual account memakan kuota plan (SettingsController /plan
-- membandingkan COUNT(*) dengan service_plan.account_limit). Sekolah
-- dengan tiga ratus pembeli pameran akan diminta naik taraf untuk
-- pelanggan yang tidak wujud — bil yang salah, bukan kekacauan kosmetik.
--
-- Legacy melakukan perkara yang sama: satu sales_acc_id per SP.
--
-- KOSNYA, dan ia mesti dijaga di tempat lain:
--   baki akaun ini TIDAK bermakna — ia jumlah gabungan orang asing
--   penyata akaun ini tidak boleh dihantar kepada sesiapa
--   FIFO mesti disekat, jika tidak bayaran seorang menutup invois orang
--   lain
--   lebihan mesti ditolak, jika tidak advance terapung antara orang yang
--   tiada kaitan
ALTER TABLE account
  MODIFY COLUMN account_type VARCHAR(50) NULL COMMENT 'NULL=pelanggan biasa, ADHOC=akaun jualan adhoc';

-- Satu akaun ADHOC per SP sedia ada.
INSERT INTO account (sp_code, account_no, account_name, account_type,
                     status, cached_balance, created_at, updated_at, version)
SELECT sp.sp_code, 'ADHOC-SALES', 'Jualan Adhoc', 'ADHOC',
       'ACTIVE', 0, NOW(), NOW(), 0
FROM   service_provider sp
WHERE  NOT EXISTS (
        SELECT 1 FROM account a
        WHERE a.sp_code = sp.sp_code AND a.account_type = 'ADHOC');

-- Indeks: setiap laluan yang mengira atau menyenaraikan akaun perlu
-- mengecualikan ADHOC, dan itu tapisan yang kerap.
CREATE INDEX idx_account_type ON account (sp_code, account_type);
