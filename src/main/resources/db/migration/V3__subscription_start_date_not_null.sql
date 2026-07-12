-- Tutup lubang idempotency: NULL tidak berlanggar dalam UNIQUE index MySQL,
-- jadi uk_subscr (account_id, product_id, start_date) tidak melindungi
-- apa-apa apabila start_date NULL. Langganan mesti ada tarikh mula.

UPDATE account_subscription SET start_date = CURDATE() WHERE start_date IS NULL;

ALTER TABLE account_subscription
  MODIFY start_date DATE NOT NULL;
