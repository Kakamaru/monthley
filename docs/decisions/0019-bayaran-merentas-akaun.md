# ADR 0019 — Bayaran merentas akaun

- **Status:** Diterima
- **Tarikh:** 20 Ogos 2026
- **Berkaitan:** [ADR 0007](0007-online-payment-guards.md), [ADR 0018](0018-bayaran-online-satu-sp.md), [ADR 0009](0009-advance-payment.md)

---

## Konteks

Pelanggan boleh mempunyai beberapa akaun dengan SP yang sama — dua unit
dalam skim yang sama, atau dua anak di sekolah yang sama. Portal
memaparkan kesemuanya, dan butang "Bayar Semua" wujud pada skrin itu.

Sehingga kini butang itu tidak berfungsi.

---

## Keputusan

**Satu bayaran boleh merangkumi beberapa akaun, asalkan SP sama.**

**Bayaran merentas akaun TIDAK boleh melebihi jumlah invois.**

Bayaran satu akaun kekal seperti sedia ada: lebihan menjadi advance
(ADR 0009).

---

## Sebab

### Kenapa satu SP sahaja

ADR 0018: `absorb` dan kadar yuran berbeza antara SP, dan rujukan bank
membawa satu `sp_code`. Tiada apa berubah di sini.

### Kenapa tiada advance merentas akaun

Advance ialah kredit pada SATU akaun. Bayaran RM500 untuk dua akaun yang
berjumlah RM414.40 meninggalkan RM85.60 — dan tiada jawapan yang betul
untuk "akaun mana".

Memecahkannya mengikut nisbah bermakna pelanggan yang membayar lebih untuk
satu akaun tertentu mendapat kredit tersebar pada akaun lain. Meletakkan
kesemuanya pada akaun pertama ialah pilihan sewenang-wenangnya yang
pelanggan tidak boleh jangka.

Menolak lebihan menjadikan peraturan boleh diterangkan dalam satu ayat:
bayar berbilang akaun, bayar tepat jumlahnya.

### Kenapa advance kekal untuk satu akaun

Tiada kekaburan: satu akaun, satu tempat untuk kredit. Pelanggan yang
membayar setahun sekali bergantung padanya.

---

## Bentuk

Satu transaksi gerbang. Beberapa panggilan `receivePayment` — satu bagi
setiap akaun — dan setiap satu menghasilkan resitnya sendiri.

Resit per akaun kerana resit terikat kepada akaun dalam skema: nombor
akaun tercetak di atasnya, dan penyata akaun mesti menunjukkan resit yang
menjelaskan invoisnya. Satu resit merentas dua akaun tidak mempunyai
nombor akaun untuk dicetak.

Ini corak yang sama seperti pasaran dalam talian: satu bayaran di kaunter,
pesanan berasingan bagi setiap penjual. Pembeli mendapat satu pengesahan
bayaran; setiap penjual mengeluarkan dokumennya sendiri.

Apabila MonthleyPay menggantikan ToyyibPay, resit transaksi gerbang datang
daripada MonthleyPay — lapisan berasingan daripada resit SP, dan bukan
gantian kepadanya.

---

## Yang ditolak

**Membenarkan advance dan meletakkannya pada akaun dengan baki terbesar.**
Boleh dilaksana, tetapi pelanggan tidak boleh meramalkannya tanpa membaca
dokumentasi — dan peraturan wang yang memerlukan dokumentasi untuk
difahami ialah peraturan yang salah.
