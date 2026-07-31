# ADR 0013 — Anjakan mod pada frekuensi yang lebih KASAR

- **Status:** Diterima 31 Julai 2026 — P1-P2 dilaksana; P3-P4 belum
- **Tarikh:** 31 Julai 2026
- **Meminda:** `billing-rules.md` §6 ("Tiada anjakan tambahan di aras produk")
- **Prasyarat:** V52 (d86aa16) — batal mesti membebaskan `idem_key`

---

## Konteks

Produk INSURANCE (YEAR, anchor 11, RM231.50) pada akaun MONTHLY tidak
pernah dijana. Larian Julai 2026 dengan KETIGA-TIGA mod menghasilkan
sifar invois INS.

### Fakta disahkan (bukan andaian)

Ujian production langsung, 5 akaun x 3 produk, 15 langganan ACTIVE:

- 10 dokumen dijana — M01 dan SF sahaja, `period_start` 2026-06-01
- INS: sifar, pada POSTPAID, CURRENT dan PREPAID

Aritmetik `cycleStartFor(2026-06-01, YEAR, 11)`, disemak dengan tangan:

    dateIdx   = 2026*12 + 5 = 24317
    anchorIdx = 10
    offset    = floorMod(24307, 12) = 7
    -> 2026-06-01 minus 7 bulan = 2025-11-01

Kemudian dalam `chargesFor` cabang (a):

    cursor = 2025-11-01
    isBefore(base.cycleStart 2026-06-01) -> plusMonths(12) -> 2026-11-01
    while (!cursor.isAfter(base.cycleEnd 2026-06-30))  -> tidak masuk

Gelung tidak berjalan sekali pun. Senarai kosong.

Cabang (b) — akaun masuk tengah kitaran — memerlukan `effectiveStart`
bukan null DAN jatuh dalam period asas. Kelima-belas langganan
`start_date` NULL dan akaun `start_date` NULL, jadi ia tidak berdenyut.

### Cerapan kritikal

`basePeriod` menganjak mod pada aras `charge_frequency` AKAUN. Untuk
produk yang lebih KASAR daripada akaun, anjakan itu hampir tiada kesan:
satu bulan pada kitaran dua belas bulan. Ketiga-tiga mod memberi
jawapan yang sama.

POSTPAID bermakna "bil selepas liputan". Untuk insurans tahunan itu
perbezaan SETAHUN, bukan sebulan. Mod tidak bermakna pada aras produk
yang lebih kasar.

Di bawah kod semasa, INS pertama keluar pada larian Disember 2026
(base = Nov 2026) untuk kitaran Nov 2026 – Okt 2027. Kitaran
Nov 2025 – Okt 2026 dan semua sebelumnya TIDAK PERNAH dicaj — dilangkau
senyap, tanpa ralat.

---

## Keputusan

**Anjakan mod dikenakan SEKALI, pada frekuensi yang lebih KASAR antara
akaun dan produk.**

Rumusan setara:

| Mod | Kitaran yang dicaj (aras produk) |
|---|---|
| POSTPAID | kitaran terakhir yang sudah SELESAI |
| CURRENT | kitaran yang MENGANDUNGI bulan larian |
| PREPAID | kitaran BERIKUTNYA |

`account.charge_frequency` kekal menentukan UFUK — berapa kitaran
ditarik dalam satu larian. Yang berubah hanya DI MANA anjakan mod
dikenakan.

### Pengesahan merentas kombinasi

| Akaun | Produk | Lebih kasar | Larian Jul 2026 | Hasil |
|---|---|---|---|---|
| MONTHLY | MONTHLY | MONTHLY | POST -1 bulan | Jun 2026 (kekal) |
| MONTHLY | YEAR anchor 11 | YEAR | POST -1 kitaran | **Nov 2024 – Okt 2025** |
| YEARLY | MONTHLY | YEAR | CURRENT | 12 caj Jan-Dis (kekal) |
| QUARTERLY | MONTHLY | QUARTER | CURRENT | Jul/Ogos/Sep (kekal) |

Tiga daripada empat tidak berubah. Yang berubah ialah kes yang hari ini
menghasilkan sifar.

### Proration — TIDAK berubah

`chargePoint = MAX(cycleStart, effectiveStart)` kekal. Start Charging
diisi (akaun ATAU langganan, lihat 0263ce0) -> prorate dari situ;
kosong -> kitaran dicaj PENUH.

Contoh: INS anchor 11, kitaran Nov 2024 – Okt 2025, `start_charging`
1 Jan 2025 -> prorate Jan 2025 hingga hujung kitaran. Tiada
`start_charging` -> RM231.50 penuh.

### Idempotency naik pangkat

Di bawah peraturan ini resolver memulangkan kitaran YANG SAMA pada
setiap larian sepanjang kitaran itu. `idem_key` yang menapis ulangan.

Ini BUKAN pelanggaran prinsip 2 (stateless atas stateful): "sudah
dijana?" dijawab dengan BERTANYA kekangan DB, bukan dengan penunjuk
boleh-ubah seperti `last_charged_period`. Tiada state baharu disimpan.

Tetapi `idem_key` berpindah daripada pertahanan mendalam kepada
peraturan sebenar. Kalau ia tersilap, caj HILANG senyap — bukan
sekadar terlepas pendua. Itu sebabnya V52 prasyarat: sebelumnya batal
menyekat penjanaan semula selamanya.

Soalan terbuka 11 (`idem_key` guna `period_start`, bukan `period_id`)
menjadi lebih penting di bawah keputusan ini.

---

## Kesan

### Ujian yang bertukar makna

`yearlyProductNotChargedOutsideAnchor` bertajuk "Bug yearly-setiap-
Januari LARUT" dan menuntut produk YEAR anchor Ogos tidak dicaj pada
sebelas bulan bukan-anchor. Ia akan MERAH.

Bug legacy ialah "dicaj setiap Januari, BERULANG". Di bawah keputusan
ini kitaran dicaj SEKALI dan `idem_key` menghalang selebihnya.
Mekanisme berbeza, hasil berbeza. Ujian ditulis semula untuk mengunci
"sekali sahaja", bukan "tidak pernah".

`yearlyProductChargesOnlyAtAnchor` dan `nullAnchorMeansJanuary` kekal
hijau — kitaran yang mengandungi base memang kitaran anchor itu.

### Tandatangan berubah

`chargesFor` memerlukan `runMonth` dan `mode`; ia tidak menerimanya
sekarang.

### createInvoice semua-atau-tiada

Baris INS akan hadir pada SETIAP larian, bukan sekali setahun. Apabila
`splitByProduct` dimatikan, satu dokumen membawa semua baris dan satu
baris pendua menggugurkan seluruh invois — TODO sedia ada dalam
`DocumentService.createInvoice`.

Hari ini ini jarang menggigit kerana resolver memulangkan kosong untuk
bulan bukan-anchor. Selepas ini ia menjadi laluan biasa untuk SP yang
mematikan split.

---

## Fasa pelaksanaan

| Fasa | Kandungan | Guard |
|---|---|---|
| P1 | `PeriodResolver` — fungsi tulen, tiada DB | `PeriodResolverTest` |
| P2 | Wiring: tandatangan, `InvoiceCalculator` | ujian integrasi |
| P3 | `createInvoice` semua-atau-tiada | ujian split dimatikan |
| P4 | `billing-rules.md` §6 dipinda | guard 5 |

Boleh berhenti selepas mana-mana fasa tanpa merosakkan sistem.

---

## Alternatif ditolak

**Anjak DUA kali (akaun, kemudian produk).** Pecah pada kes paling
biasa: akaun MONTHLY + produk MONTHLY, POSTPAID, larian Julai memberi
Mei — dua bulan ke belakang. Data production mengesahkan Jun betul.

**Biarkan seperti sekarang; minta SP mengisi `start_charging`.**
Cabang (b) memerlukan `effectiveStart` jatuh DALAM period asas, jadi
akaun yang didaftarkan hari ini dengan tarikh lampau tetap terlepas.
Dan ia meletakkan beban pada SP untuk menampung peraturan yang salah.

**Fallback `created_at` untuk `effectiveStart`.** Ditolak: memprorate
atau mencaj berdasarkan bila kerani menaip. Rujuk e3e207e, di mana
dakwaan bahawa prorata rosak ditarik balik atas sebab yang sama.

---

## Rujukan

- `billing-rules.md` §6 — anchor month, titik caj
- ADR 0006 — alokasi peringkat line
- V18, V52 — `idem_key`
- 0263ce0 — suis proration membaca dua tempat
