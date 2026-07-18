-- anchor_month: TINYINT -> INT, selaras dengan entity Product.anchorMonth (Integer).
--
-- Ditemui oleh ddl-auto: validate (18 Julai 2026): Hibernate petakan Integer ke
-- INTEGER, bukan TINYINT, jadi validasi skema gagal masa boot. Sebelum validate
-- diaktifkan, ketidakpadanan ini senyap — anchor_month ialah teras enjin bil,
-- jadi bahaya untuk kekal tidak disemak.
--
-- DB ikut kod: anchor_month ialah bulan (konseptual integer), semua kod layan
-- Integer. Julat 1-12 muat TINYINT, tetapi konsistensi jenis mengatasi 3 bait.
ALTER TABLE product MODIFY anchor_month INT NULL;
