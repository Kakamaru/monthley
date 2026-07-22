# ADR 0005 — Alokasi Peringkat Line (belum dilaksana)

- **Status:** DITANGGUH (technical debt — perlu dirancang)
- **Tarikh dicatat:** 23 Julai 2026

---

## Masalah

`fi_allocation` mengaitkan bayaran pada peringkat **DOKUMEN**:
`debit_document_id` (invois) <-> `credit_document_id` (resit).

Contoh: Invois ada 2 line — Yuran RM100 + Parking RM50 (total RM150).
Pelanggan bayar RM150. Sistem rekod "RM150 knock invois", TAPI **tidak
pecah** RM100 -> Yuran, RM50 -> Parking.

Akibatnya sistem TIDAK tahu bayaran untuk item/produk mana. Laporan yang
perlukan pecahan produk hanya boleh ANGGAR:
- Kutipan Ikut Produk (dashboard) — anggaran proportional sahaja.
- Pendapatan ikut produk/kategori.
- Analisis kutipan per jenis caj.

Punca teknikal query berganda: JOIN fi_allocation ON debit_document_id =
line.document_id menggandakan satu alokasi dengan setiap line invois
(cth by-product bagi RM13,377 sedangkan kutipan sebenar RM1,350).

---

## Keputusan dicadangkan (belum dilaksana)

Alokasi ke peringkat LINE, bukan dokumen:

1. `fi_allocation` += `debit_document_line_id` (link ke line spesifik).
2. **PaymentService** — masa terima bayaran, agih ke line ikut FIFO
   DALAM invois (Yuran dulu, lebihan ke Parking), bukan level invois.
3. Migration + **backfill** alokasi sedia ada — agih semula ke line.
4. Kemas kini semua query guna fi_allocation (baki, kutipan ikut produk,
   statement).

---

## Kenapa DITANGGUH

- Sentuh **core payment** yang baru dikukuhkan (idempotency ADR 0004,
  AllocationGuard). Risiko tinggi kalau tergesa-gesa.
- Melibatkan **duit + data sedia ada** (backfill) — perlu ujian teliti.
- Perlu ADR penuh + rancangan migration + backfill strategy.

Bukan kerja tampal — projek berasingan yang dirancang, dibuat masa fresh.

## Sementara itu

Dashboard v2 (Kutipan Ikut Produk, Invois vs Kutipan yang bergantung
alokasi produk) DITANGGUH sehingga line-level siap. Endpoint dashboard
kekal di versi P2 (summary asas + carta kutipan + produk utama +
transaksi + tunggakan) yang sudah verified.

## Rujukan
- `0004-manual-payment-idempotency.md`
- `accounting-invariants.md`
