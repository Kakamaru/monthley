-- Katalog modul mesti wujud walaupun produk belum.
--
-- V66 memasukkan ref_module melalui JOIN kepada product, yang memerlukan
-- SP platform dan produk EXP/ADU/SUM/MEMO sudah wujud. Pada mesin
-- pembangunan ia berfungsi kerana kedua-duanya dicipta dengan tangan
-- sebelum V66 ditulis.
--
-- Pada pemasangan BAHARU, kedua-dua JOIN gagal dan sifar baris dimasukkan
-- — katalog kosong, dan tiada SP boleh melanggan apa-apa. Ditemui semasa
-- deploy pertama ke VPS, iaitu satu-satunya tempat DB benar-benar kosong.
--
-- Pengajaran: migrasi seed tidak boleh bergantung pada data yang migrasi
-- lain tidak cipta. product_id dipautkan KEMUDIAN, bila produk wujud.

INSERT INTO ref_module (code, name, description, business_types, sort_order, status)
SELECT x.code, x.name, x.descr, x.biz, x.sort, 'ACTIVE'
FROM (
    SELECT 'PERBELANJAAN' AS code, 'Perbelanjaan' AS name,
           'Rekod perbelanjaan, invois pembekal, baucar bayaran, dan buku tunai. Terus masuk ke Untung Rugi.' AS descr,
           NULL AS biz, 1 AS sort
    UNION ALL SELECT 'ADUAN', 'Aduan',
           'Terima dan urus aduan penghuni dengan status dan susulan.', NULL, 2
    UNION ALL SELECT 'SUMBANGAN', 'Sumbangan',
           'Rekod sumbangan dan derma, dengan resit dan laporan.', NULL, 3
    UNION ALL SELECT 'MEMO', 'Memo',
           'Hebahan dan memo kepada penghuni.', NULL, 4
) x
WHERE NOT EXISTS (SELECT 1 FROM ref_module m WHERE m.code = x.code);

-- Paut produk jika ia sudah wujud. Kosong pada pemasangan baharu —
-- superadmin memautkannya melalui skrin Katalog Modul.
UPDATE ref_module m
JOIN   service_provider owner ON owner.is_platform_owner = 1
JOIN   product p ON p.sp_code = owner.sp_code
                AND p.code = CASE m.code
                    WHEN 'PERBELANJAAN' THEN 'EXP'
                    WHEN 'ADUAN'        THEN 'ADU'
                    WHEN 'SUMBANGAN'    THEN 'SUM'
                    WHEN 'MEMO'         THEN 'MEMO'
                END
SET    m.product_id = p.id
WHERE  m.product_id IS NULL;
