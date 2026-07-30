-- Butiran penerima dan catatan untuk invois adhoc.
--
-- issued_to_name dan issued_to_email sudah wujud sejak V1. Yang hilang:
-- nombor telefon (borang menandakannya WAJIB) dan catatan.
--
-- KENAPA PADA DOKUMEN dan bukan akaun. Semua invois adhoc berkongsi SATU
-- akaun ADHOC-SALES (V50), jadi akaun tidak boleh membawa butiran
-- pembeli. Setiap invois membawa penerimanya sendiri.
--
-- Untuk invois BERAKAUN medan ini kekal NULL — butiran datang daripada
-- akaun, dan menduplikasinya bermakna dua sumber untuk satu fakta.
ALTER TABLE financial_document
  ADD COLUMN issued_to_phone VARCHAR(30) NULL AFTER issued_to_email,
  ADD COLUMN remarks VARCHAR(500) NULL AFTER issued_to_phone;
