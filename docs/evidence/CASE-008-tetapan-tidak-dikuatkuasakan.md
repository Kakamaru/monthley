# CASE-008 — Tetapan dikumpul tetapi tidak dikuatkuasakan

- **Tarikh:** 28 Julai 2026
- **Status:** CORAK DIKENAL PASTI. LIMA daripada enam dibetulkan. Tinggal kes 1 (penapis produk pada skrin langganan masih hidup dalam UI sahaja; guard backend dipasang dalam CASE-007).
- **Skop:** sistemik, bukan modul tunggal

## Corak

UI mengumpul input atau memaparkan tetapan. Backend menerimanya dan
mengabaikannya secara **senyap** — tiada ralat, tiada log, tiada amaran.

Pengguna melihat kawalan, menggunakannya, dan menganggap ia berkesan.

## Enam kes ditemui dalam dua hari

| # | Tetapan / input | Dikumpul di | Dikuatkuasakan? |
|---|---|---|---|
| 1 | Penapis "produk belum dilanggan" | Skrin langganan | TIDAK — penapis hidup dalam frontend sahaja (CASE-007) |
| 2 | `allow_selective` | Tetapan Resit | TIDAK — dibetulkan 45589ef |
| 3 | `paymentDate` | Manual Payment | TIDAK — dibetulkan 374e3c4 |
| 4 | `remarks` | Manual Payment | ~~TIDAK~~ **DIBETULKAN** — V39 + resit PDF |
| 5 | `receipt_prefix`, `invoice_prefix`, size, starts-from | Tetapan Resit/Invois | ~~TIDAK~~ **DIBETULKAN** — ADR 0012 |
| 6 | `enable_manual_payment` | Tetapan Resit | ~~TIDAK~~ **DIBETULKAN** — semakan dalam ManualPaymentController |

## Kesan mengikut keterukan

**Kes 3 paling teruk setakat ini.** Tarikh bayaran yang salah bermakna
penyata menyusun resit pada hari yang salah, baki berjalan salah untuk
hari-hari antara, dan rekonsiliasi bank tidak tally.

**Kes 2** menyebabkan kerani menanda enam invois dan sistem membayar
yang ketujuh.

**Kes 5** menyebabkan SP menetapkan prefix 'R26' dan melihat 'RCP000032'.

**Kes 6** membenarkan bayaran manual walaupun SP mematikannya —
mengelak kawalan yang sengaja dipasang.

## Kenapa ia berulang

Tiada apa yang menghubungkan tetapan kepada tingkah laku. Menambah
lajur, mendedahkannya dalam DTO, dan memaparkan toggle adalah tiga
langkah yang boleh diselesaikan tanpa langkah keempat — menguatkuasakan
— dan tiada apa yang gagal apabila langkah itu terlepas.

Ujian tidak menangkapnya kerana tiada ujian ditulis untuk tingkah laku
yang tidak pernah dilaksanakan.

## Pembetulan

**Setiap tetapan memerlukan ujian yang membuktikan ia dikuatkuasakan.**
Bukan ujian bahawa ia disimpan dan dibaca — ujian bahawa menukarnya
MENGUBAH tingkah laku sistem.

Contoh yang betul (ditulis semasa membetulkan kes 2 dan 3):
- `PaymentDateTest` — tarikh dua hari lepas muncul pada resit, rekod
  bayaran DAN ledger
- `SubscriptionOverlapGuardTest` — langganan ACTIVE menyekat, ENDED tidak

## Tertunggak

- Kes 1 — guard dipasang, tetapi penapis UI masih satu-satunya penapis
  pada skrin langganan
- Kes 4 — `remarks` perlu medan dalam `NewPayment` dan `payment`
- Kes 5 — penomboran dokumen; menyentuh SEMUA jenis dokumen, perlukan ADR
- Kes 6 — `enable_manual_payment` perlu semakan dalam
  `ManualPaymentController`

## Rujukan
- CASE-007 (kes 1)
- 0011-split-ikut-tempoh.md (penemuan kes 2)
