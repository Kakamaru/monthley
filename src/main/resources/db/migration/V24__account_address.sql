-- V24 — Alamat akaun (berasingan dari billto) + baris ke-4 untuk billto.
--
-- Contoh JMB: akaun = pemilik unit, billto = penyewa yang bayar bil.
-- Dua alamat berbeza, dua-dua perlu disimpan.
--
-- Legacy guna 4 baris alamat. Data prod: addr_4 diisi 584 rekod,
-- billto_addr_4 diisi 6,994 rekod — jadi baris ke-4 WAJIB ada, kalau
-- tidak migrasi hilang data. Skema baru asalnya 3 baris; ini betulkan.
--
-- Bandar TIDAK disimpan berasingan (ikut legacy) — masuk addr_line.
-- Poskod auto-isi negeri sahaja melalui table postcode (V23).

ALTER TABLE account
  ADD COLUMN addr_line1 VARCHAR(255) NULL AFTER member_mobile,
  ADD COLUMN addr_line2 VARCHAR(255) NULL AFTER addr_line1,
  ADD COLUMN addr_line3 VARCHAR(255) NULL AFTER addr_line2,
  ADD COLUMN addr_line4 VARCHAR(255) NULL AFTER addr_line3,
  ADD COLUMN addr_postcode VARCHAR(10) NULL AFTER addr_line4,
  ADD COLUMN addr_state VARCHAR(100) NULL AFTER addr_postcode,
  ADD COLUMN addr_country VARCHAR(100) NULL AFTER addr_state,
  ADD COLUMN billto_addr_line4 VARCHAR(255) NULL AFTER billto_addr_line3;
