# ADR 0004 — Idempotency Bayaran Manual (elak double-entry)

- **Status:** Diterima
- **Tarikh:** 22 Julai 2026
- **Konteks:** Bayaran manual (`POST /payments/manual`) kadang menghasilkan
  DUA resit untuk satu bayaran sebenar — double-entry. Punca: klien hantar
  request dua kali (rangkaian perlahan, klik berganda, retry auto browser/
  proxy, refresh halaman).

---

## Masalah

Legacy SUDAH ada perlindungan frontend — butang jadi dim selepas klik.
Double-entry **tetap berlaku**. Sebab frontend guard boleh dipintas:

1. **Retry auto** — browser/proxy retry request yang nampak timeout, walaupun
   server sebenarnya sudah terima.
2. **Request berganda tiba dahulu** — dua request sampai server SEBELUM
   response pertama balik (butang belum sempat dim, atau state belum update).
3. **Refresh/navigate** — pengguna refresh selepas submit, resubmit borang.

Ini keluarga bug yang sama dengan CASE-001 family 4 (double-submit). Ref no
berturutan menunjukkan sequence generator jalan dua kali — bukan dua bayaran
disengajakan.

**Pengajaran:** perlindungan mesti di BACKEND. Frontend guard perlu (UX) tapi
tidak mencukupi. Satu permintaan = satu pemprosesan, tidak kira berapa kali
request tiba.

---

## Keputusan

**Idempotency token dari klien.**

1. Klien menjana `idempotencyKey` (UUID) SEKALI per sesi bayar (bila borang
   bayar dibuka / sebelum submit pertama), hantar dalam request body.
2. Server, sebelum memproses:
   - Cari `payment` dengan `idempotency_key` sama (untuk SP tersebut).
   - Kalau WUJUD → pulangkan resit sedia ada (tiada pemprosesan baharu).
   - Kalau BELUM → proses seperti biasa, simpan key pada payment.
3. **Race condition** (dua request lolos semakan serentak) ditutup oleh
   **unique constraint** `uk_payment_idem (sp_code, idempotency_key)`:
   - Insert kedua gagal dgn duplicate-key.
   - Tangkap exception, pulangkan resit yang pertama berjaya.

Unique constraint = sempadan sebenar (macam AllocationGuard untuk
over-allocation). Semakan app-level sahaja ada lubang race; DB constraint
menutupnya.

---

## Implikasi / Kerja

- **Migration V29**: `payment.idempotency_key VARCHAR(64) NULL` + unique index
  `uk_payment_idem (sp_code, idempotency_key)`. NULL dibenarkan (bayaran lama
  + laluan tanpa key), banyak NULL OK dalam unique index MySQL.
- **PaymentService.receivePayment**: terima `idempotencyKey`; semak-dahulu +
  tangkap DataIntegrityViolation → pulang resit sedia ada.
- **Frontend**: jana `crypto.randomUUID()` bila borang bayar dibuka; hantar
  dalam `/manual`. Reset selepas berjaya (sesi bayar baharu = key baharu).
- Laluan lain (adjustment) sudah ada idempotency sendiri (source_ref).

---

## Rujukan

- `evidence/CASE-001-balance-mismatch-A0124.md` (double-submit, family 4)
- `accounting-invariants.md` §2 (token idempotency untuk catatan manual)
- `decisions/0003-account-adjustment.md` (corak source_ref idempotency)
