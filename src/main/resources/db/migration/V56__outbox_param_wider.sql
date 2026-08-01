-- param2_val: 100 -> 500 aksara.
--
-- Laporan penjanaan menyimpan ringkasan larian sebagai SNAPSHOT:
-- tarikh, akaun diimbas, invois dikeluarkan, jumlah, dan senarai tempoh
-- yang dibilkan.
--
-- Senarai tempoh itu yang melebihi had. Akaun tahunan dengan produk
-- bulanan menghasilkan DUA BELAS tempoh dalam satu larian
-- ('Januari 2026,Februari 2026,...') — lebih 180 aksara, dan MySQL
-- memotongnya. Tempoh yang hilang bermakna laporan MENIPU tentang apa
-- yang dibilkan.
--
-- SNAPSHOT, bukan kira semula. Alternatifnya ialah menyoal kiraan
-- semasa menghantar, yang memerlukan port baharu (renderer duduk dalam
-- notification, allowedDependencies = { shared }, dan tidak boleh
-- menyoal financial_document) — dan memberi nombor yang BERBEZA kalau
-- kerani menjana sekali lagi antara beratur dan penghantaran.
--
-- Laporan melaporkan LARIAN ITU, bukan keadaan sekarang.
--
-- param1_val kekal 100: ia memegang nama SP, bukan senarai.

ALTER TABLE email_outbox
  MODIFY COLUMN param2_val VARCHAR(500) NULL;
