-- Alokasi peringkat line — rujuk docs/decisions/0006-line-level-allocation-plan.md
--
-- Satu baris alokasi per LINE (bukan per dokumen). SUM(amount) per
-- debit_document_id kekal sama, jadi AllocationGuard + semua query baki
-- sedia ada tidak terjejas.
--
-- NULL dibenarkan: DEBIT_NOTE/CREDIT_NOTE tiada line (disahkan 23/7/2026),
-- jadi alokasi dokumen tersebut kekal peringkat dokumen.

ALTER TABLE fi_allocation ADD COLUMN debit_document_line_id BIGINT NULL;
CREATE INDEX ix_alloc_debit_line ON fi_allocation (debit_document_line_id);
