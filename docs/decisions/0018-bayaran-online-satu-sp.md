# ADR 0018 — Bayaran online terhad kepada SATU SP

- **Status:** Diterima
- **Tarikh:** 17 Ogos 2026
- **Berkaitan:** [ADR 0007](0007-online-payment-guards.md)

---

## Konteks

Pelanggan boleh mempunyai akaun dengan beberapa SP — JMB tempat tinggal,
kelab, sekolah anak. Portal pelanggan memaparkan semuanya dalam satu
senarai, dan butang "Bayar Semua" wujud pada skrin itu.

Bolehkah satu bayaran online merangkumi invois daripada BEBERAPA SP?

README pernah mencatat "gabungan invois merentas SP" sebagai hasrat. Nota
itu ditulis sebelum model yuran difahami.

---

## Keputusan

**Satu bayaran online = satu SP.**

Pelanggan boleh membayar beberapa invois merentas beberapa AKAUN, asalkan
akaun-akaun itu milik SP yang sama (ADR 0019). Bayaran merentas SP mesti
dipecahkan kepada beberapa transaksi.

---

## Sebab

### 1. Yuran berbeza mengikut SP

`sp_payment_setting.absorb` menentukan siapa menanggung yuran gerbang:

| `absorb` | Kesan |
|---|---|
| `0` | Yuran DITAMBAH kepada bayaran — pelanggan bayar RM100 + RM1.50 |
| `1` | SP menyerap — pelanggan bayar RM100, SP terima RM98.50 |

Dua SP dalam satu bayaran boleh mempunyai tetapan berbeza. Satu jumlah
tunggal tidak boleh mewakili kedua-duanya: pelanggan tidak tahu berapa
yang ditambah untuk SP yang mana, dan SP tidak tahu berapa yang diserap.

### 2. Kadar yuran bergantung pada bilangan invois

`rate_single` dikenakan untuk bayaran satu invois; `rate_multi` untuk
bayaran pelbagai. Merentas SP, "bilangan invois" menjadi kabur — dua invois
daripada dua SP ialah satu invois setiap satu, atau dua semuanya?

### 3. Rujukan bank membawa SATU sp_code

Rujukan gerbang berbentuk `sp_code` + kaunter base36 (corak legacy:
`001T3B6H`). Rujukan ini muncul dalam penyata bank, dan prefix SP
membolehkan wang diagihkan tanpa menyoal pangkalan data.

Bayaran merentas SP tidak mempunyai satu `sp_code` untuk diletakkan di
situ. Pengagihan kembali memerlukan pertanyaan, dan itu menghapuskan sebab
format ini wujud.

### 4. Resit adalah per SP

Setiap SP mengeluarkan resitnya sendiri dengan penomborannya sendiri
(ADR 0012). Satu bayaran merentas tiga SP menghasilkan tiga resit daripada
satu transaksi gerbang — dan pembatalan salah satu daripadanya
meninggalkan transaksi gerbang yang sebahagiannya dibalikkan.

---

## Kesan pada UI

Butang "Bayar Semua" pada portal pelanggan mesti dikumpulkan MENGIKUT SP.
Pelanggan dengan akaun di tiga SP melihat tiga jumlah, bukan satu.

Ini kelihatan kurang kemas, tetapi ia jujur: pelanggan memang berhutang
kepada tiga organisasi berbeza, dan setiap satu mempunyai syarat
bayarannya sendiri.

---

## Yang ditolak

**Memecahkan satu bayaran gerbang kepada beberapa SP di belakang tabir.**
Boleh dilakukan secara teknikal — satu transaksi gerbang, beberapa rekod
payment. Tetapi wang tiba sebagai satu jumlah dalam penyata bank, dan
memadankannya kembali kepada SP memerlukan tepat maklumat yang format
rujukan direka untuk menghapuskan keperluannya.
