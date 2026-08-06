-- Gugurkan cached_balance: tidak pernah DIBACA oleh mana-mana kod.
-- Sejak ADR 0009, baki datang dari VIEW account_balance sahaja.
-- Lajur ini masih DITULIS (nilai 0) oleh AdhocInvoiceService dan fixture ujian,
-- jadi ia nampak hidup padahal mati — perangkap untuk sesiapa yang jumpa kemudian.
-- Semua nilai disahkan 0 sebelum digugurkan; tiada VIEW atau index bergantung padanya.
ALTER TABLE account
  DROP COLUMN cached_balance,
  DROP COLUMN cached_balance_at;
