-- Account Adjustment: Credit Note (Reduction) + Debit Note (Additional).
-- Rujuk docs/decisions/0003-account-adjustment.md.

-- 1. doc_type ENUM -> VARCHAR(20). Validation value sah dikawal di domain
--    (enum Java DocumentType), bukan constraint DB. Align dgn @Enumerated(STRING).
--    Sokong CREDIT_NOTE, DEBIT_NOTE (dan doc_type masa depan) tanpa ALTER enum.
ALTER TABLE financial_document MODIFY COLUMN doc_type VARCHAR(20) NOT NULL;

-- 2. source_ref: token idempotency dari klien (elak double-submit, CASE-001 family 4).
--    Unik per SP: submit kedua dgn token sama ditolak di peringkat DB.
ALTER TABLE financial_document ADD COLUMN source_ref VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_doc_source_ref ON financial_document (sp_code, source_ref);
