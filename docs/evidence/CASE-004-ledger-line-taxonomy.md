# CASE-004 — Taksonomi baris ledger legacy

- **Tarikh:** 25 Julai 2026
- **Sumber:** p302_my produksi (DBeaver, VPN) + m_statement_dark.prpt
- **Tujuan:** menentukan cara migrasi mengklasifikasikan baris
  mon_fi_doc_txn tanpa bergantung pada teks

## Penemuan utama

`txn_code` ialah jenis baris eksplisit. Legacy sudah memilikinya;
migrasi hanya perlu memetakan, bukan meneka daripada prod_descr.

| txn_code | baris | prod_id NULL | subscr_id NULL | label unik | Makna | Skema baharu |
|---|---|---|---|---|---|---|
| M1000 | 2,556,765 | 1,997 | 134,572 | 11,158 | Caj produk/langganan | PRODUCT |
| M1500 | 52,776 | 52,766 | semua | 6 | Caj lewat | PENALTY |
| M2000 | 33,735 | 33,642 | semua | 20 | Advance | ADVANCE (tidak diimport) |
| M3000 | 25,674 | 25,206 | semua | 39 | Pelarasan akaun | ADJUSTMENT |
| M0001 | 3,976 | 2,862 | semua | 3 | Baki pembukaan | OPENING_BALANCE |

Hanya M1000 membawa subscr_id. Empat kod lain 100% NULL — pengesahan
bebas bahawa txn_code memang jenis baris, bukan kod rawak.

## prod_descr tidak boleh dipercayai

**Dua ejaan dalam kod yang sama.** M2000 mempunyai 33,735 baris tetapi
hanya 33,595 berbunyi "Advanced Payment"; sampel lain berbunyi
"Advance Payment" tanpa 'd'. Migrasi ikut teks akan terlepas 140 baris
tanpa sebarang tanda.

**Teks ialah snapshot sejarah.** prod_descr disalin pada masa posting.
Produk yang dinamakan semula atau dipadam kemudian menyebabkan baris
lama tidak lagi sepadan dengan mon_sp_prod. Itu tingkah laku BETUL
untuk ledger, tetapi bermakna rekonsiliasi mesti ikut prod_id.

**Tempoh ditanam dalam string.** Contoh sebenar:
"WATER 15 FEB 2024 - 15 JUN 2024" (4,121), "WATER 1 Sept 2024 - 30 Nov
2024" (3,480), "Arrears Carried Forward As At 02 Oct" (6,999),
"Arrears Carried Forward As at 31 August" (3,540). Perhatikan "As At"
lawan "As at" — dua ejaan, dua rekod berbeza. Tiada tahun pada
sebahagiannya. Skema baharu menyimpan period_start / period_end
sebagai DATE.

**Ujian kawalan.** "Mengaji tambahan" (11,544 baris) menunjukkan 0
prod_id NULL dan 1 prod_id unik — produk sebenar, bersih. "Account
Adjustment" (dua varian, 24,764 baris) menunjukkan SEMUA NULL dan
SIFAR prod_id — label sintetik tulen. prod_id IS NULL + txn_code
ialah pembeza yang boleh dipercayai.

## Perangkap NULL dalam NOT IN

Percubaan pertama menggunakan `WHERE prod_descr NOT IN (SELECT descr_
FROM mon_sp_prod)` mengembalikan sifar baris. Sebabnya mon_sp_prod
mengandungi 4 descr_ NULL daripada 1,478 rekod; satu NULL sahaja
menjadikan setiap perbandingan UNKNOWN. Guna NOT EXISTS.

## Arrears bukan sintetik tulen

"Arrears Carried Forward": 12,817 baris, 2,862 prod_id NULL — jadi
9,955 baris MEMPUNYAI prod_id, merentas 5 prod_id berbeza, tersebar
antara M0001 dan M1000. Tunggakan bawa hadapan selalunya diposkan
terhadap produk sebenar. Baik untuk migrasi (baki pembukaan boleh
dipetakan ikut produk), tetapi skrip tidak boleh menganggap M0001 dan
"Arrears" ialah set yang sama.

## Anomali satu-rekod

| Kes | Anomali |
|---|---|
| "Advanced Payment" | 33,594 prod_id NULL, 1 mempunyai prod_id |
| "Late Charges 10% per annum" | 3,831 NULL, 1 mempunyai prod_id |
| M1500 | 52,766 NULL daripada 52,776 — 10 terkeluar corak |
| Dokumen aktif, semua ledger batal | 1 (RCP R242390, RM143.50) |

Corak yang sama berulang di seluruh siasatan: satu leak RM80
(CASE-002), satu J00 pendua (CASE-001), dan sekarang ini. Ledger
legacy kukuh; yang hanyut ialah cache di atasnya. Skrip migrasi mesti
LOG anomali, bukan menyenyapkannya.

## Resit: amaun dokumen lawan jumlah ledger

Lima resit aktif dengan `ABS(doc.amt_ - SUM(txn.amt_)) > 0.005`:

| ref_no | sp_code | doc_amt | ledger_amt | beza | kod |
|---|---|---|---|---|---|
| RCP14000 | 0011 | 66.00 | 63.00 | 3.00 | M1000 |
| R00003344 | 001D | 190.00 | 160.00 | 30.00 | M1000 |
| R242390 | 0023 | 143.50 | 112.95 | 30.55 | M1000 (semua sts C) |
| rSRI001635 | MY000090 | 320.00 | 10.00 | 310.00 | M1000 |
| rSRI010887 | MY000090 | 130.00 | 30.00 | 100.00 | M1000 |

Kelima-limanya M1000 sahaja — tiada M2000. Bezanya bukan penyimpangan
amaun; ia baris ADVANCE yang tidak pernah dipos. Di bawah keputusan 11
ADR 0010 (dokumen authoritative, ADVANCE diterbitkan) kesemuanya
sembuh secara automatik.

R242390 mempunyai masalah kedua: dokumen sts A, tetapi semua baris
ledger DAN semua link sts C (50.00 + 8.50 + 54.45 = 112.95). Import
naif akan mencipta kredit hantu RM143.50. Ia satu-satunya kes di
seluruh produksi.

## Peraturan migrasi

1. Klasifikasi ikut `txn_code`, tidak sekali-kali ikut `prod_descr`.
2. Rekonsil ikut `prod_id`; simpan `prod_descr` sebagai snapshot papar.
3. Gugurkan baris M2000; ambil `mon_sp_fi_doc.amt_` sebagai amaun
   dokumen yang authoritative.
4. Dokumen sts A yang SEMUA baris ledgernya sts C dianggap batal.
5. `sp_code` mempunyai lebar berbeza — '0011' (4) dan 'MY000090' (8),
   lajur varchar(12). Jangan andai lebar tetap.
6. Kunci perniagaan dokumen ialah (sp_code, ref_no), BUKAN ref_no
   sahaja — 'RCP3964' wujud dalam sekurang-kurangnya empat SP.
7. `dt_acc_amt` / `cr_acc_amt` ialah SNAPSHOT BAKI BERJALAN, bukan
   amaun transaksi. Amaun ada dalam `amt_`. (CASE-001)
8. Log setiap anomali; jangan senyapkan.

## Rujukan
- 0010-penyata-akaun.md
- CASE-001-balance-mismatch-A0124.md
- CASE-002-amt_actv-scenario-catalog.md
