-- Catatan bayaran (CASE-008 kes 4).
--
-- ManualPaymentRequest.remarks diterima oleh controller sejak awal dan
-- DIBUANG di situ: NewPayment tiada medan untuknya, dan payment tiada
-- lajur. Kerani menaip catatan, menekan Simpan, dan catatan itu hilang.
--
-- Corak yang sama seperti paymentDate (374e3c4) — UI mengumpul, backend
-- mengabaikan senyap.
--
-- Duduk pada payment, bukan financial_document: ia butiran BAYARAN,
-- bersama kaedah dan nombor rujukan. Resit legacy memaparkannya sebagai
-- 'Payment Notes'.
--
-- Legacy mengehadkan 100 aksara; 255 lebih longgar tanpa risiko.
ALTER TABLE payment
  ADD COLUMN remarks VARCHAR(255) NULL AFTER payment_ref_no;
