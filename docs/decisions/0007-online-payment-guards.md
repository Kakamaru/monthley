# ADR 0007 — Guard Payment Online (kekangan reka bentuk)

- **Status:** Diterima sebagai kekangan; modul belum dibina
- **Tarikh:** 23 Julai 2026
- **Sumber:** `evidence/CASE-003-online-payment-integration.md`

---

## Konteks

Legacy mencatat 33 anomali merentas 20,885 resit online / 18 bulan.
Terburuk: RM310,000 dicatat untuk bayaran RM310. Satu kes (RM2,575 wang
hantu) terlepas pandang **5 bulan**.

Punca dari siasatan: shared mutable state dalam callback handler, amaun
resit DIKIRA dari baki invois (bukan diambil dari gateway), dan tiada
reconciliation automatik.

**Kedudukan kita:** modul online payment BELUM dibina — disahkan 23/7/2026,
rujukan FPX yang wujud hanyalah enum `PaymentMethod` dan medan rujukan
manual payment. Maka guard di bawah ialah **kekangan reka bentuk**, bukan
kerja pembaikan. Kosnya hampir sifar sekarang; mahal kalau ditangguh.

---

## Keputusan

### Diterima — dilaksana bersama modul

1. **Amaun resit MESTI diambil dari gateway, jangan dikira.**
   `receipt.amount := gateway.netAmount`. Bayaran ialah fakta luaran (bank
   sudah debit); baki invois ialah keadaan dalaman yang boleh berubah
   antara masa bill dicipta dan callback tiba.

2. **Callback handler stateless.** Tiada medan boleh-ubah pada bean; semua
   konteks dalam parameter atau local variable. Ujian kekal: 100 callback
   serentak dengan amaun berbeza, sahkan setiap resit padan bayarannya.

3. **Idempotency pada `gateway_txn_ref`** (UNIQUE). Gateway retry callback
   jika tiada 200 OK. Corak sama ADR 0004.

4. **Reconciliation harian** — banding setiap bayaran gateway berjaya
   dengan resit. Alert bila: bayaran tanpa resit, resit tanpa bayaran,
   atau amaun tak padan.

### Murah sekarang, mahal nanti

5. **Model yuran eksplisit** — `payment` perlu `gross/fee/net`, invariant
   `gross = fee + net`. Sekarang hanya ada amount/allocated/deposit.
   Legacy simpan yuran sebagai "beza yang kita jangka" (1.50/2.00/0),
   menyukarkan pengesanan anomali kerana perlu tahu senarai beza yang OK.

### DITANGGUH

6. **Jenis `Money`** — 46 fail guna BigDecimal. Bahaya sebenar ada di
   SEMPADAN integrasi (gateway hantar sen, kita simpan ringgit); dalaman
   sistem sudah konsisten satu unit (semua decimal(15,2) ringgit).
   Dua pilihan: penuh (jaminan compiler merentas 46 fail) atau bersasar
   (jenis nilai di sempadan gateway sahaja).
   **Diputuskan semasa membina modul online.**

---

## Nota analisis: Kes E mungkin bukan punca berasingan

CASE-003 merekod Kes E (~0.1%) sebagai "punca tidak diketahui":

| Bill | Tambahan | 0.1% patut |
|---|---|---|
| 360.00 | +0.36 | 0.36 padan |
| 240.00 | +0.24 | 0.24 padan |
| 180.00 | +0.18 | 0.18 padan |
| 142.00 | +0.14 | 0.14 padan |
| 320.00 | +0.20 | 0.32 TIDAK padan |
| 122.00 | +0.22 | 0.12 TIDAK padan |

Dua yang tidak padan: **+0.20 ialah 0.1% daripada RM200**, dan **+0.22
ialah 0.1% daripada RM220** — kedua-duanya amaun bill yang munasabah.

Hipotesis: Kes E bukan punca ketiga. Ia yuran 0.1% yang biasanya betul,
tetapi kadangkala amaun ASAS pengiraan bocor dari transaksi serentak —
mekanisme sama Kes A/B/C.

**Boleh diuji:** cari transaksi serentak dengan bill RM200 dan RM220 pada
saat yang sama. Jika betul, punca berkurang dari tiga kepada dua.

## Rujukan
- `evidence/CASE-003-online-payment-integration.md`
- `0004-manual-payment-idempotency.md` (corak idempotency)
- `evidence/CASE-001-balance-mismatch-A0124.md`
