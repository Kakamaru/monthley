# ADR 0020 — Modul Sumbangan (kutipan derma)

- **Status:** Diterima
- **Tarikh:** 22 Ogos 2026
- **Berkaitan:** [ADR 0007](0007-online-payment-guards.md), [ADR 0012](0012-penomboran-dokumen.md), [ADR 0019](0019-bayaran-merentas-akaun.md)

---

## Konteks

SP mencipta kempen derma dan berkongsi pautan awam. Sesiapa boleh menderma
— penghuni, orang luar, ahli keluarga yang tinggal di negeri lain. Mereka
tiada akaun dengan SP dan tidak akan mendaftar untuk menderma RM50.

Ini berbeza daripada setiap aliran wang lain dalam sistem: bayaran sedia
ada bermula daripada invois yang wujud, dibuat oleh pelanggan yang log
masuk, terhadap akaun yang mereka miliki.

---

## Keputusan

### 1. Bayaran awam, tanpa log masuk

Endpoint derma tidak memerlukan pengesahan. Kempen dikenal pasti melalui
slug dalam URL awam.

Ini bukan kelonggaran keselamatan: tiada apa untuk dilindungi. Penderma
tidak mengakses data sesiapa, tidak menjelaskan hutang sesiapa, dan
memberikan wang. Menuntut log masuk menghalang derma tanpa melindungi
apa-apa.

Yang MASIH dilindungi: amaun datang daripada gerbang (ADR 0007 #1), kempen
mesti aktif dan dalam tempoh, dan kadar yuran tidak boleh dipengaruhi oleh
permintaan.

### 2. Derma menghasilkan RESIT sahaja

Tiada invois. Derma bukan hutang — tiada apa yang dijelaskan.

Invois yang dicipta sesaat sebelum dibayar ialah dokumen kosong yang wujud
hanya untuk memuaskan bentuk. Ia mengotorkan senarai invois, muncul dalam
laporan tunggakan selama sesaat, dan menambah nombor invois yang tidak
pernah dilihat sesiapa.

Ledger: kredit ke akaun hasil derma, debit ke tunai/bank — sama seperti
bayaran lain, tetapi tanpa langkah menyelesaikan akaun belum terima.

### 3. Kempen mengatasi tetapan SP untuk yuran

absorb wujud pada sp_payment_setting, tetapi kempen mempunyai tetapannya
sendiri.

Sebabnya: SP boleh menyerap yuran untuk yuran bulanan (kos perniagaan)
tetapi meminta penderma menanggungnya untuk kutipan derma — RM1.50
daripada derma RM50 ialah 3% yang tidak pergi kepada tujuan.

Kempen tanpa tetapan mewarisi SP.

---

## Bentuk

Dua jadual:

| Jadual | Kandungan |
|---|---|
| `donation_campaign` | Nama, slug, poster, sasaran, tarikh, tetapan borang, tetapan yuran |
| `donation` | Satu derma: nama penderma, e-mel, telefon, amaun, status |

Derma yang berjaya mencipta dokumen jenis RECEIPT pada akaun kutipan SP,
dan rekod payment yang merujuknya.

Jadual `donation` menyimpan maklumat penderma kerana ia BUKAN pelanggan:
tiada account_id untuk membawa nama, e-mel, atau telefon.

---

## Yang ditolak

**Mencipta akaun untuk setiap penderma.** Derma sekali daripada orang luar
tidak sepatutnya meninggalkan akaun kekal yang muncul dalam senarai akaun,
kiraan kuota, dan laporan tunggakan selama-lamanya.

**Menggunakan akaun ADHOC-SALES.** Akaun itu wujud untuk jualan yang
menghasilkan invois. Derma tidak, dan mencampurkannya bermakna penyata
akaun ADHOC mengandungi dua jenis transaksi dengan makna berbeza.
