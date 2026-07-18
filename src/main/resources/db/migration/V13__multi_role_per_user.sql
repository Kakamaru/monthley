-- =====================================================================
-- V13 — Satu pengguna boleh ada BEBERAPA peranan dalam SP yang sama
--
-- Sebab: pengasingan tugas. AJK/management boleh jadi Admin (urus sistem)
-- TANPA kebenaran terima duit. Kalau perlu dua-dua, tambah dua kali.
-- =====================================================================

-- Kunci unik (sp_code, user_id, role) sudah dicipta oleh migrasi terdahulu —
-- lihat CREATE TABLE sp_membership. ALTER di sini adalah sisa: ia cuba DROP
-- 'uk_sp_membership' yang tidak pernah wujud (nama sebenar: 'uk_membership'),
-- dan gagal atas skema bersih. Hanya deskripsi peranan tinggal di sini.

-- Perjelas deskripsi — Admin TIDAK termasuk terimaan bayaran.
UPDATE sp_role SET description =
  'Urus produk, akaun, bil & tetapan. TIDAK termasuk terimaan bayaran.'
WHERE code = 'SP_ADMIN';

UPDATE sp_role SET description =
  'Rekod terimaan bayaran & jana resit sahaja.'
WHERE code = 'CLERK';

UPDATE sp_role SET description =
  'Lihat sahaja — akaun, bil & laporan. Tiada kebenaran mengubah.'
WHERE code = 'VIEWER';
