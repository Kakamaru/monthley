-- ADR 0016 peringkat A: struktur sahaja, tiada pembacaan diubah lagi.
--
-- Rapidevelop ialah SP yang membil SP lain (dogfood). Tiga lajur baharu:
--
-- 1. is_platform_owner — supaya kod tidak pernah menyebut 'SP0000'. Onboarding,
--    kelulusan modul, dan katalog produk semuanya perlu tahu "SP mana yang
--    membilkan SP lain"; tiga tempat hardcode ialah corak yang Guard 6 halang.
--
-- 2. billing_account_id — pautan SP kepada akaunnya di bawah SP platform.
--    Dicipta automatik semasa onboarding supaya nama tidak boleh menyimpang
--    antara dua rekod.
--
-- 3. service_plan.product_id — harga akan hidup di produk sahaja. Lajur
--    price_monthly/price_yearly SENGAJA dikekalkan buat masa ini; ia digugurkan
--    dalam migrasi berasingan selepas disahkan tiada lagi pembaca (corak V61).

ALTER TABLE service_provider
  ADD COLUMN is_platform_owner  TINYINT(1) NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN billing_account_id BIGINT     NULL     AFTER is_platform_owner,
  ADD CONSTRAINT fk_sp_billing_account
      FOREIGN KEY (billing_account_id) REFERENCES account(id);

-- Hanya satu SP boleh jadi pemilik platform. Indeks unik pada nilai 1 sahaja:
-- NULL berulang dibenarkan dalam indeks unik MySQL, jadi 0 dipetakan ke NULL.
ALTER TABLE service_provider
  ADD COLUMN platform_owner_flag TINYINT(1)
      GENERATED ALWAYS AS (CASE WHEN is_platform_owner = 1 THEN 1 ELSE NULL END) VIRTUAL,
  ADD UNIQUE KEY uk_satu_platform_owner (platform_owner_flag);

ALTER TABLE service_plan
  ADD COLUMN product_id BIGINT NULL AFTER account_limit,
  ADD CONSTRAINT fk_plan_product
      FOREIGN KEY (product_id) REFERENCES product(id);

-- Tandakan Rapidevelop. Dilakukan melalui nama, bukan kod, supaya migrasi ini
-- kekal betul walaupun kod SP berubah lagi.
UPDATE service_provider
SET    is_platform_owner = 1
WHERE  name LIKE 'Rapidevelop%';
