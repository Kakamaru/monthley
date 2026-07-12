-- financial_document.period tertinggal — entiti FinancialDocument perlukannya.
ALTER TABLE financial_document
  ADD COLUMN period VARCHAR(7) NULL AFTER doc_date;
