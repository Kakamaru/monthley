-- ADR 0012 — turutan menyimpan prefix yang terakhir digunakan.
--
-- Tetapan ialah NIAT SP; turutan ialah KEADAAN BERJALAN. next() membaca
-- prefix daripada sp_document_setting setiap kali, dan membandingkannya
-- dengan yang terakhir digunakan. Berbeza -> nombor reset ke no_start.
--
-- Prefix menandakan tempoh dalam data produksi (K19 = 2019), jadi
-- menukarnya bermakna kitaran baharu bermula.
--
-- Lajur prefix dan padding sedia ada KEKAL sebagai sandaran untuk SP
-- yang tidak pernah membuka skrin tetapan.
ALTER TABLE document_number_sequence
  ADD COLUMN last_prefix VARCHAR(10) NULL AFTER prefix;

-- Baris sedia ada: prefix semasa ialah yang terakhir digunakan.
UPDATE document_number_sequence SET last_prefix = prefix WHERE last_prefix IS NULL;
