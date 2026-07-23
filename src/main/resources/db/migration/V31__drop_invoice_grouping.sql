-- ADR 0008: split_invoice_by_product ialah sumber kebenaran.
-- invoice_grouping ialah rekaan lebih tanpa padanan legacy, dan dua lajur
-- untuk satu keputusan ialah corak CASE-002 (menyimpang antara satu sama lain).
ALTER TABLE sp_document_setting DROP COLUMN invoice_grouping;
