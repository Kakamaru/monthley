# ADR 0002 — Penyata akaun aras transaksi + advance ≠ deposit

- **Status:** Diterima
- **Tarikh:** 21 Julai 2026
- **Konteks:** Penyata akaun (endpoint `/accounts/{id}/statement`) mula-mula dibina aras DOKUMEN (1 invois = 1 baris). Legacy Monthley papar aras TRANSAKSI/ITEM: setiap baris invois & setiap knock resit = 1 baris dengan item + period + baki berjalan.

---

## Masalah

Aras dokumen sembunyikan butiran yang pengguna perlukan:
- Invois multi-line (cth M04: 12× PRKING + 1 INSURANCE dalam 1 doc RM950.59) papar sebagai satu baris tunggal — item & period liputan hilang.
- Resit yang knock beberapa invois tak tunjuk invois mana dibayar.
- Lebihan bayaran (advance) tak nampak sebagai entri berasingan.

Legacy papar setiap item sebagai baris tersendiri, descending, dengan baki berjalan.

---

## Keputusan

Endpoint statement ubah ke **aras transaksi**. Tiga sumber baris di-UNION:

1. **Baris invois** — 1 baris per `financial_document_line` aktif.
   Debit = `amount + tax_amount`. Item = `description`. Period = period LIPUTAN
   baris (`COALESCE(fi_period.name_, DATE_FORMAT(period_start,'%b %Y'))`).

2. **Knock resit** — 1 baris per `fi_allocation` (status ACTIVE).
   Kredit = `amount`. Item **derive dari invois yang dipotong** (rujuk `debit_document_id → doc_no`), papar `Bayaran → INV000021`.

3. **Baris advance** — bila resit ada lebihan.
   Kredit = `resit.amount − SUM(alokasi ACTIVE resit itu)`.

Tertib menaik `(ts, kind, seq)` untuk kira baki berjalan (kind 0=invois, 1=knock, 2=advance), kemudian reverse ke **descending** untuk paparan + pagination.

### Kenapa item knock rujuk doc-no, bukan line

`fi_allocation` beroperasi aras DOKUMEN — tiada `line_id`. Untuk invois multi-line, tiada satu `description` yang jujur wakil satu knock. Merujuk `doc_no` invois adalah tepat; meneka satu line adalah salah.

### Advance = Cr gl 2273 (tak perlu join ledger)

`resit − Σ(alokasi ACTIVE)` secara matematik **sama** dengan Cr gl 2273 (Advance),
kerana balanced-entry invariant (accounting-invariants.md): setiap resit
`Dr Bank = Cr AR (knock) + Cr Advance (lebihan)`. Jadi statement kira advance
secara self-contained tanpa join ledger — angka dijamin sama.

Rujuk billing-rules §9 (baki diterbitkan dengan SUM, tak simpan — elak drift).

---

## Advance ≠ Deposit (istilah)

**Advance** = lebihan bayaran pelanggan. Boleh auto-knock tunggakan masa depan
(billing-rules §3.7). Ledger: Cr gl **2273** (Advance). Legacy-generator-analysis
§275–276 memang guna istilah "advance" (baki −RM1,800 selepas overpay).

**Deposit** = wang cagaran dengan SOP tersendiri (cth deposit sewa). **TIDAK**
masuk aliran settlement automatik. `account.deposit_amount` medan berasingan.

### Misnomer dalam kod (tindakan tertunggak)

`PaymentResult.deposit()` dan `FifoAllocator` guna "deposit" sedangkan maksudnya
**advance**. Statement guna istilah betul ("Bayaran pendahuluan (advance)").
Kod perlu rename `deposit → advance` supaya konsisten dengan domain dan elak
keliru dengan deposit cagaran sebenar.

---

## Akibat

- Statement kini padan legacy (aras item, descending, baki berjalan, pagination).
- Tiada perubahan pada engine/allocation — guna `fi_allocation` sedia ada.
- Test: `AccountControllerTest.statementAtTxnLevel` (knock + advance + multi-line).
- **Untested (declared):** pagination slice `page > 0`.
- **Tertunggak:** rename `PaymentResult.deposit()` → `advance()`; seragamkan
  format period invois (INSURANCE papar "2025", PRKING papar "January, 2025").

---

## Rujukan

- `billing-rules.md` §3.7 (auto-knock advance), §9 (baki diterbitkan)
- `accounting-invariants.md` (balanced-entry, tiga laluan allocation)
- `legacy-generator-analysis.md` §275–276 (istilah advance)
- Commit: penyata aras txn (endpoint + test)
