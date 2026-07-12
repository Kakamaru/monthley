-- BaseEntity ada updated_at/updated_by/version. Jadual ledger asal cuma ada
-- created_at/created_by (sebab immutable). Selaraskan supaya entiti padan jadual.

ALTER TABLE journal_entry
  ADD COLUMN updated_at DATETIME NULL AFTER created_by,
  ADD COLUMN updated_by VARCHAR(64) NULL AFTER updated_at;

-- fi_allocation & chart_of_accounts sudah ada updated_at/version dalam V1 — skip.
