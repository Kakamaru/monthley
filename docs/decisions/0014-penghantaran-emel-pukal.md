# ADR 0014 — Penghantaran e-mel pukal (outbox)

- **Status:** Diterima 1 Ogos 2026 — P1-P7 belum dilaksana
- **Tarikh:** 1 Ogos 2026
- **Prasyarat:** V42 (token pautan awam), ADR 0010 (penyata), ADR 0011 (token bukan dokumen)

---

## Konteks

Legacy menghantar lebih 10,000 e-mel penyata pada 1 haribulan setiap
bulan, ditambah satu laporan penjanaan kepada setiap SP. Monthley baharu
menghantar resit dan invois adhoc sahaja — kedua-duanya satu-satu,
segerak, selepas transaksi commit.

Corak itu tidak boleh diskalakan. Sepuluh ribu panggilan HTTP dalam satu
gelung bermakna: larian yang mengambil masa berjam-jam, kegagalan
separuh jalan tanpa cara mencuba semula dengan selamat, dan tiada
keterlihatan sehingga seseorang mengadu.

### Fakta disahkan (bukan andaian)

Daripada e-mel production 1 Ogos 2026:

- Sri Mutiara: 1,075 akaun aktif, 1,948 invois, **353 saat** satu SP
- Cap masa laporan: 12:31, 12:32, 12:38, 12:46, 12:51 — SP diproses
  BERTURUTAN, bukan serentak
- E-mel penyata pelanggan melampirkan PDF **dan** memberi pautan

Daripada kod:

- `auto_generate` dan `invoice_gen_day` wujud dalam `sp_document_setting`;
  **tiada kod membacanya**. Tiada `@Scheduled` untuk bil. Penjanaan
  automatik belum wujud.
- `@EnableScheduling` sudah aktif dalam `MonthleyApplication`, tanpa
  sebarang tugas.
- `GenerationOutcome` memulangkan kiraan dan `billedPeriodIds` — bukan
  senarai akaun.
- `document_access_token` mempunyai `UNIQUE(document_id)` dan FK ke
  `financial_document`.

### Cerapan kritikal

**Penyata bukan dokumen.** Ia unjuran baca-sahaja atas julat tarikh
(ADR 0010); tiada baris dalam `financial_document`. Token sedia ada
tidak boleh memegangnya.

Legacy menyelesaikan masalah yang SAMA dengan mencipta dokumen `P`
hantu untuk setiap e-mel — 51 rekod bukan-kewangan pada satu akaun
(CASE-006). V42 wujud untuk mengelakkannya.

---

## Keputusan

### 1. Jadual outbox

Penghantaran dipisahkan daripada peristiwa yang mencetuskannya.
Jana bil tidak menunggu e-mel. Kegagalan boleh dicuba semula PER BARIS —
kegagalan pada e-mel ke-5,000 tidak menghantar semula 4,999 yang
pertama.

Status per baris juga memberi keterlihatan: SP boleh melihat berapa
dihantar, berapa tertunggak, berapa gagal dan mengapa.

### 2. Token penyata: jadual BERASINGAN

`statement_access_token` — akaun + tahun, bukan `document_id`.

Alternatif yang ditolak: melonggarkan `document_access_token` supaya
`document_id` nullable dengan `account_id` dan `period` tambahan. Itu
menghasilkan lajur yang bermakna "kadang-kadang ini, kadang-kadang itu",
dan menyentuh jadual yang berfungsi untuk kes yang berbeza.

Juga ditolak: pautan tanpa token (`/pub/stmt/{accountUuid}/{tahun}`).
Tiada `revoke`, tiada kiraan lihat, dan UUID yang bocor memberi akses
kepada SEMUA tahun. V42 sengaja menghadkan pendedahan kepada satu
dokumen; prinsip yang sama terpakai.

### 3. PAUTAN, bukan lampiran

Legacy melampirkan PDF. Kita tidak.

`EmailPort` sudah merakam sebabnya, dan sebab ketiga tepat kes ini:
sepuluh ribu PDF dijana sebelum menghantar. Pada 100KB setiap satu itu
1GB melalui penyedia e-mel, untuk fail yang kebanyakannya tidak dibuka.

Pautan juga sentiasa menunjukkan keadaan SEMASA. PDF dalam peti masuk
ialah salinan beku: penyata yang dihantar sebelum bayaran diterima kekal
menunjukkan tunggakan selama-lamanya.

### 4. Pencetus: penjanaan invois, bukan jadual berasingan

Penyata dihantar apabila invois dijana, kepada **semua akaun AKTIF yang
mempunyai e-mel** — bukan hanya akaun yang menerima invois dalam larian
itu.

Draf pertama menghadkannya kepada akaun yang dibil, yang bermakna
`GenerationOutcome` perlu memulangkan `billedAccountIds`. Tidak perlu:
penyata ialah keadaan AKAUN, bukan resit bagi satu invois.

**SATU penyata per AKAUN, bukan per invois.** Satu larian boleh
menghasilkan lapan belas invois untuk satu akaun (produk berbilang x
tempoh berbilang, ADR 0011). Menghantar satu e-mel setiap satu bermakna
puluhan ribu e-mel dan peti masuk pelanggan yang tidak boleh dibaca.

Dua alamat setiap akaun: e-mel akaun dan e-mel kedua
(`billto_email_secondary`). Legacy menyimpan yang kedua pada baris
gilir sebagai `add_emails`; kita ikut.

Tiga pencetus:

| Pencetus | Status |
|---|---|
| Jana pukal (butang) | sudah ada |
| Jana tunggal (butang) | sudah ada |
| Auto pada `invoice_gen_day` | **belum wujud** |

`GenerationOutcome` tidak berubah.

### 4b. Baris membawa PARAMETER, bukan badan siap

Legacy menyimpan HTML penuh dalam `body_full` — `String.format` dengan
tiga belas argumen, templat dalam pemalar Java. Untuk lampiran ia
menyimpan nama laporan (`m_invoice_dark.prpt`) dan parameternya, bukan
PDF: fail dijana semasa menghantar, bukan semasa beratur.

Corak kedua itu betul dan digunakan untuk keseluruhan baris. Outbox
menyimpan jenis, rujukan dan parameter; badan dirender semasa
menghantar.

  jenis      STATEMENT | GENERATION_REPORT | REMINDER
  rujukan    akaun + tempoh, atau sp + tempoh
  parameter  kunci/nilai generik, corak `p_acc_no` / `p_period` legacy

Menyimpan badan siap bermakna sepuluh ribu salinan HTML setiap bulan,
dan pembetulan templat tidak menjejaskan baris yang sudah beratur.

### 4c. Satu saluran, direka untuk lebih

Lajur `channel` dengan satu nilai — `EMAIL`. SMS mungkin menyusul;
menambahnya kemudian menjadi baris data, bukan migrasi struktur.

WhatsApp DIKECUALIKAN dengan sengaja. Penghantaran pukal di sana
menyebabkan nombor disekat sebagai spam — bukan had teknikal yang boleh
dikendalikan, tetapi dasar platform yang menjadikan kes penggunaan ini
mustahil.

`body_short` legacy (versi SMS) tidak disimpan buat masa ini kerana
saluran itu tidak digunakan. Ia dirender daripada parameter yang sama
apabila diperlukan.

### 5. Reminder ialah aliran BERBEZA

Bukan variasi penyata:

| | Penyata | Reminder |
|---|---|---|
| Pencetus | jana invois | jadual |
| Penerima | akaun yang dibil | mana-mana akaun BERTUNGGAK |
| Status akaun | aktif sahaja | aktif DAN tidak aktif |

Akaun tidak aktif tidak menerima invois baharu tetapi masih berhutang.
Menganggap reminder sebagai penyata dengan penapis berbeza akan
menyembunyikan perbezaan itu.

### 6. Laporan penjanaan kepada SP

Satu e-mel per SP selepas larian: akaun diimbas, invois dikeluarkan,
jumlah, tempoh, ralat. Legacy sudah menghantarnya dan ia berguna —
tanpanya larian tengah malam yang gagal separuh jalan tidak diketahui
sehingga seseorang perasan.

Menggunakan outbox yang sama. Tujuh puluh satu e-mel kepada penerima
DALAMAN ialah ujian sebenar untuk outbox sebelum sepuluh ribu dihantar
kepada pelanggan.

---

## Fasa pelaksanaan

| Fasa | Kandungan | Guard |
|---|---|---|
| P1 | `email_outbox` + tugas berjadual + `EmailOutboxPort` | ujian: batch, cuba semula, kegagalan separa |
| P2 | Laporan penjanaan kepada SP (71 e-mel) | ujian production langsung |
| P3 | `statement_access_token` + endpoint `/pub/stmt/{token}` | ujian: token, revoke, tenant |
| P4 | Penyata pukal melalui outbox (10,000) | ujian: hanya akaun DIBIL |
| P5 | Penjanaan automatik (`auto_generate`, `invoice_gen_day`) | ujian: tetapan mengubah tingkah laku |
| P6 | Halaman awam penyata + butang Pay | perlukan gerbang pembayaran |
| P7 | Reminder | ujian: akaun tidak aktif TERMASUK |

Boleh berhenti selepas mana-mana fasa. P2 mengesahkan outbox pada skala
kecil sebelum P4 mempercayainya dengan sepuluh ribu.

P6 bergantung pada integrasi gerbang yang belum wujud.

---

## Risiko & mitigasi

**Had kadar penyedia.** Had Resend untuk akaun ini belum disemak. Batch
bersaiz boleh laras dan jeda antara batch; kalau had dilanggar, baris
kekal PENDING dan dicuba semula, bukan hilang.

**E-mel berganda.** `UNIQUE(sp_code, jenis, rujukan)` pada outbox —
larian kedua untuk tempoh yang sama tidak boleh menghasilkan baris
kedua. Corak sama seperti `idem_key` pada baris dokumen.

**Alamat tidak sah.** Baris ditandakan FAILED dengan sebab; ia tidak
menghalang baris lain. SP melihat senarai dan membetulkan alamat.

**Outbox membesar.** Sepuluh ribu baris sebulan. Baris SENT lebih lama
daripada tempoh yang ditetapkan boleh dipangkas; keputusan itu ditangguh
sehingga ada data sebenar.

---

## Alternatif ditolak

**Hantar dalam transaksi jana bil.** Satu penyedia yang perlahan menahan
kunci baris untuk keseluruhan larian, dan kegagalan e-mel menggulung
invois. Duit lebih penting daripada notifikasi.

**Baris gilir dalam memori di samping DB (corak legacy).** Legacy
menulis DUA kali untuk satu peristiwa:

```java
notifService.add(q);   // ke DB
notifQueue.put(q);     // ke Hazelcast — "this will block"
```

Kalau DB berjaya dan baris gilir gagal, `catch` hanya log dan baris
kekal 'P' selama-lamanya tanpa sesiapa memprosesnya. Kalau baris gilir
berjaya dan transaksi digulung kemudian, e-mel keluar untuk invois yang
tidak wujud.

Satu tulisan, ke DB, dalam transaksi yang sama. Tugas berjadual
meninjau. Tiada perkhidmatan kedua untuk gagal secara berasingan.

**Baris gilir luaran (Redis, RabbitMQ).** Satu lagi perkhidmatan untuk
dijalankan, dipantau dan dipulihkan. Sepuluh ribu baris sebulan tidak
memerlukannya; jadual MySQL dengan status ialah baris gilir yang mencukupi
dan boleh disoal dengan SQL biasa.

**Satu jadual token untuk semua.** Lihat keputusan 2.

---

## Rujukan

- ADR 0010 — penyata akaun
- ADR 0011 — token dalam jadual sendiri, bukan dokumen hantu
- CASE-006 — aliran e-mel legacy
- V42 — `document_access_token`
- `EmailPort` — pautan bukan lampiran
