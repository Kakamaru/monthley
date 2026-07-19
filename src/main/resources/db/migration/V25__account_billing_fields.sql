-- V25 — Field billing tambahan untuk Add Account (ikut design portal).
--
-- deposit_amount + opening_amount: dipapar di borang tetapi DISEMBUNYIKAN
-- buat masa ini (pengurusan deposit = fasa 2; opening = migrasi J00).
-- Column disediakan supaya data boleh disimpan bila fungsi tersebut siap.
--
-- billto_email_secondary: email kedua penerima bil (design: Secondary Email).
-- remarks: catatan bebas pada akaun.

ALTER TABLE account
  ADD COLUMN deposit_amount DECIMAL(15,2) NULL DEFAULT 0.00 AFTER billto_country,
  ADD COLUMN opening_amount DECIMAL(15,2) NULL DEFAULT 0.00 AFTER deposit_amount,
  ADD COLUMN billto_email_secondary VARCHAR(255) NULL AFTER billto_email,
  ADD COLUMN remarks VARCHAR(500) NULL AFTER opening_amount;
