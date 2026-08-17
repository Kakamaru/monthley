-- Modul Pengurusan Pelajar (sektor pendidikan sahaja).
--
-- Katalog dan produk sahaja pada peringkat ini; skema operasi modul
-- menyusul selepas reka bentuk skrin dimuktamadkan.
--
-- business_types = 'EDU' bermakna hanya SP pendidikan melihatnya dalam
-- senarai modul yang boleh dimohon. JMB yang membuka Tetapan > Manage Plan
-- tidak akan nampak modul ini langsung — penapis sektor menghalang SP
-- daripada memohon sesuatu yang tidak berkenaan dengan mereka.

-- ---------------------------------------------------------------------------
-- 1) Produk pada SP platform
-- ---------------------------------------------------------------------------
INSERT INTO product
  (sp_code, code, name, category_id, charge_frequency, unit_rate, account_limit,
   main_product, mandatory, prorated, late_penalty, status,
   created_at, updated_at, created_by, version)
SELECT owner.sp_code, 'PLJR', 'Modul Pengurusan Pelajar',
       (SELECT id FROM product_category
        WHERE sp_code = owner.sp_code AND name = 'ADDITIONAL MODUL' LIMIT 1),
       'MONTHLY', 20.00, NULL, 0, 0, 0, 0, 'ACTIVE',
       NOW(), NOW(), 'seed', 0
FROM   service_provider owner
WHERE  owner.is_platform_owner = 1
  AND  NOT EXISTS (
      SELECT 1 FROM product p
      WHERE p.sp_code = owner.sp_code AND p.code = 'PLJR');

-- ---------------------------------------------------------------------------
-- 2) Katalog modul
-- ---------------------------------------------------------------------------
INSERT INTO ref_module (code, name, description, business_types, sort_order, status)
SELECT 'PELAJAR', 'Pengurusan Pelajar',
       'Rekod pelajar, kelas, penjaga, dan kaitan dengan akaun bil.',
       'EDU', 5, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ref_module WHERE code = 'PELAJAR');

-- ---------------------------------------------------------------------------
-- 3) Paut produk ke katalog
-- ---------------------------------------------------------------------------
UPDATE ref_module m
JOIN   service_provider owner ON owner.is_platform_owner = 1
JOIN   product p ON p.sp_code = owner.sp_code AND p.code = 'PLJR'
SET    m.product_id = p.id
WHERE  m.code = 'PELAJAR' AND m.product_id IS NULL;
