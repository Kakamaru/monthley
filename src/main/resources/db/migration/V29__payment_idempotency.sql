-- Idempotency bayaran manual — elak double-entry.
-- Rujuk docs/decisions/0004-manual-payment-idempotency.md.

-- Token idempotency dari klien (UUID). NULL dibenarkan (bayaran lama +
-- laluan tanpa key). Unique per SP: request kedua dgn key sama ditolak
-- di peringkat DB (tutup race condition dua request serentak).
ALTER TABLE payment ADD COLUMN idempotency_key VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_payment_idem ON payment (sp_code, idempotency_key);
