-- journal_entry.source_document_id menjadi rujukan POLIMORFIK.
--
-- V1 menganggap setiap jurnal berasal daripada financial_document, dan itu
-- benar sehingga modul Perbelanjaan wujud. Sekarang source_type menentukan
-- jadual mana yang dirujuk:
--
--   INVOICE, PAYMENT, PENALTY, ...  -> financial_document
--   EXP_INVOICE                      -> exp_invoice
--   EXP_PAYMENT                      -> exp_payment
--   EXP_CASH                         -> exp_cash_entry
--
-- MySQL tiada FK polimorfik, jadi kekangan tunggal ke satu jadual tidak
-- boleh bertahan. Setiap modul baharu akan terlanggar dinding yang sama.
--
-- APA YANG TIDAK HILANG: jurnal seimbang (LedgerPort menolak yang tidak),
-- jurnal tidak boleh diubah (status POSTED + pembalikan contra), dan
-- dokumen tidak boleh dipadam sambil jurnal hidup (DELETE_RULE pada FK
-- lain kekal). Kepercayaan pada data kewangan bergantung pada catatan
-- berkembar yang seimbang, bukan pada FK ini.
--
-- GANTIAN: JournalSourceInvariantTest mengesahkan SETIAP journal_entry
-- merujuk baris yang benar-benar wujud dalam jadual yang betul mengikut
-- source_type. Itu liputan LEBIH LUAS daripada FK yang digugurkan — FK
-- hanya menyemak financial_document; ujian menyemak semua jenis.

ALTER TABLE journal_entry DROP FOREIGN KEY fk_journal_doc;

-- Indeks dikekalkan: FK menciptanya, dan carian ikut dokumen sumber masih
-- diperlukan untuk jejak audit.
ALTER TABLE journal_entry ADD INDEX idx_journal_source (source_type, source_document_id);

-- exp_setting kini extends BaseEntity, yang mengisytiharkan created_at dan
-- created_by. V67 meninggalkannya kerana tetapan dicipta sekali dan jarang
-- diubah — tetapi tanpa lajur ini, updated_at tidak diisi oleh auditing
-- Spring dan INSERT gagal dengan 'Column updated_at cannot be null'.
ALTER TABLE exp_setting
  ADD COLUMN created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER bank_gl_account_id,
  ADD COLUMN created_by VARCHAR(64) NULL                                AFTER created_at,
  MODIFY COLUMN updated_at DATETIME NULL;
