-- Catatan bayaran pada VIEW resit.
--
-- V39 menambah payment.remarks; VIEW mesti mendedahkannya supaya modul
-- statement boleh membacanya (allowedDependencies = { shared }).
CREATE OR REPLACE VIEW receipt_header AS
SELECT d.id                AS receipt_id,
       d.sp_code           AS sp_code,
       d.account_id        AS account_id,
       d.doc_no            AS receipt_no,
       d.doc_date          AS receipt_date,
       d.created_at        AS issued_at,
       d.amount + d.tax_amount AS amount_paid,
       d.status            AS status,
       p.method            AS payment_method,
       p.payment_ref_no    AS payment_ref_no,
       p.remarks           AS remarks,
       p.allocated_amount  AS allocated_amount,
       p.deposit_amount    AS deposit_amount
FROM       financial_document d
LEFT JOIN  payment p ON p.receipt_document_id = d.id
WHERE d.doc_type = 'RECEIPT'
  AND d.account_id IS NOT NULL;
