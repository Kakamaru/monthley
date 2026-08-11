-- Jenis sumber ledger untuk modul Perbelanjaan.
--
-- source_type ialah ENUM dalam DB, jadi menambah nilai memerlukan ALTER.
-- Tanpa ini, posting perbelanjaan gagal dengan "Data truncated for column
-- 'source_type'" — ralat yang langsung tidak menunjukkan puncanya.
--
-- Jenis BAHARU dan bukan guna semula INVOICE/PAYMENT: invois jualan dan
-- invois belian bertentangan arah, dan sp_ledger_line serta mana-mana
-- laporan yang menapis source_type='INVOICE' akan mencampurkan keduanya.

ALTER TABLE journal_entry
  MODIFY COLUMN source_type
    ENUM('INVOICE','PAYMENT','PENALTY','CANCELLATION','WRITEOFF','ADJUSTMENT',
         'OPENING','EXP_INVOICE','EXP_PAYMENT','EXP_CASH') NOT NULL;
