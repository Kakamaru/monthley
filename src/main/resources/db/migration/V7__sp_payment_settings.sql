ALTER TABLE service_provider
  ADD COLUMN min_pymt_amount DECIMAL(15,2) NULL,
  ADD COLUMN allow_selective TINYINT(1) NOT NULL DEFAULT 0;
