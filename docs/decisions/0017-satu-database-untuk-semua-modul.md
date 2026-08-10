# ADR 0017 — Satu database untuk semua modul

- **Status**: Diterima
- **Tarikh**: 10 Ogos 2026
- **Berkaitan**: ADR 0009 (baki tunggal), ADR 0016 (modul tambahan)

## Konteks

Dalam sistem lama, setiap modul tambahan mendapat database sendiri. Modul
Expenses dibangunkan sebagai aplikasi standalone dengan database `expenses`
dan `monthley_expenses` yang berasingan daripada `p302_my`. Itu keputusan
sedar: modul baharu sentiasa mendapat database asing.

Soalan yang sama timbul semula ketika modul Expenses hendak dibawa masuk ke
Monthley baharu, dan ia akan timbul lagi untuk Aduan, Memo, Sumbangan, dan
modul sektor sekolah.

## Keputusan

**Semua modul berkongsi satu database: `monthley_new`.**

Nama jadual berprefiks mengikut modul (`exp_`, `adu_`, `memo_`) — sama
seperti `sp_` dan `fi_` yang sedia ada. Prefiks menandakan pemilikan;
database tidak.

## Alasan

Keputusan lama betul untuk sistem lama dan salah untuk yang ini, kerana
keadaannya berbeza:

| | Sistem lama | Monthley baharu |
|---|---|---|
| Pengasingan modul | Hanya database yang boleh melakukannya | Spring Modulith: `allowedDependencies`, pakej `internal`, disemak kompiler |
| Ledger | Tiada — `bal_amt`/`amt_actv` bertaburan | Ledger berkembar; setiap modul mesti pos ke situ |
| Untung Rugi | Tiada laporan bersepadu | Ada, dan perbelanjaan ialah bahagian tengahnya |

Di sistem lama, database berasingan ialah satu-satunya cara mengasingkan
modul. Di sini, Modulith sudah melakukannya — terbukti ketika modul
`platform` tidak dapat menyentuh `account` sehingga `account :: api`
ditambah secara eksplisit ke `allowedDependencies` (ADR 0016 C1).

Jadi kita mendapat pengasingan **tanpa** kehilangan empat perkara berikut:

**1. Ledger tidak boleh merentas database.** Invois pembekal mesti mempos
`Dr Belanja / Cr AP` ke `journal_entry` yang sama dengan invois jualan.
Kalau tidak, Untung Rugi terpaksa menggabungkan dua sumber — dan ia tidak
lagi boleh dibuktikan seimbang.

**2. FK tidak boleh merentas database.** `exp_invoice.sp_code` perlu FK ke
`service_provider`; `exp_category.gl_account_id` perlu FK ke
`chart_of_accounts`. Tanpa FK, tiada apa yang menghalang baris yatim, dan
kita sudah melihat betapa mahalnya membaiki data yatim dalam `p302_my`.

**3. Satu transaksi.** Invois pembekal dan catatan jurnalnya mesti komit
bersama. Dua database bermakna dua transaksi: invois boleh wujud tanpa
jurnal, atau jurnal tanpa invois, dan tiada siapa perasan sehingga
penyata tidak seimbang.

**4. Semakan hak.** `ModuleGuard` membaca `sp_module` pada setiap endpoint
tulis. Database berasingan menjadikan setiap semakan panggilan rentas.

## Bukti dari pengalaman

Sepanjang siasatan `p302_my`, halangan terbesar ialah `mpay` berada dalam
database berasingan di belakang VPN berbeza. Cross-database join mustahil,
jadi setiap semakan menjadi dua langkah manual dengan padanan tangan.
Anomali bayaran online mengambil masa berhari-hari untuk disahkan sebahagian
besarnya kerana ini.

Meletakkan perbelanjaan dalam database berasingan bermakna soalan "kenapa
Untung Rugi tidak seimbang" akan menghadapi halangan yang sama.

## Akibat

**Baik:**
- Satu ledger, satu takrifan Untung Rugi
- FK menguatkuasakan integriti merentas modul
- Satu transaksi, satu backup, satu migrasi
- Penyiasatan boleh menggunakan satu sambungan

**Kos:**
- Satu database membesar; jadual modul mesti berprefiks supaya pemilikan
  jelas
- Modul tidak boleh diskalakan atau dipindahkan secara berasingan tanpa
  kerja migrasi. Diterima: tiada keperluan yang diketahui, dan Modulith
  membenarkan pemisahan kemudian kalau ia benar-benar timbul

## Sengaja TIDAK dibuat

- **Skema berasingan dalam pelayan yang sama** — menyelesaikan sebahagian
  masalah (join masih boleh) tetapi menambah kelayakan dan konfigurasi
  tanpa faedah yang jelas
- **Database read-replica untuk laporan** — masalah prestasi yang belum
  wujud
