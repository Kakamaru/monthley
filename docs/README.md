# Monthley — Dokumentasi

> **Mula di sini.** Dokumen ini indeks + keadaan semasa.
> Kemas kini: 31 Julai 2026

Projek: penulisan semula greenfield Monthley — SaaS bil berulang multi-tenant
oleh Rapidevelop Technology Sdn Bhd. Sistem lama (`p302_my`) masih hidup,
melayan ~71 SP. Ia rujukan, bukan asas migrasi berperingkat.

---

## Baca ikut urutan ini

| # | Dokumen | Isi |
|---|---|---|
| 1 | [`cara-kerja.md`](cara-kerja.md) | **Bagaimana** Kama & Claude bekerja. Prinsip, corak yang berkesan/gagal, persekitaran, gotchas. |
| 2 | [`domain/billing-rules.md`](domain/billing-rules.md) | Peraturan bil muktamad + bukti production |
| 3 | [`domain/legacy-generator-analysis.md`](domain/legacy-generator-analysis.md) | Enjin lama dipetakan ke reka bentuk baru: jurang, bug, perbezaan sengaja |
| 4 | [`domain/accounting-invariants.md`](domain/accounting-invariants.md) | Empat guard dari siasatan CASE-001 |
| 5 | [Isu legacy yang kita elak](#isu-legacy-yang-kita-elak) | Kes sebenar + guard yang menghalangnya berulang |

---

## Stack

| | |
|---|---|
| Backend | Spring Boot 4.1, Spring Modulith 2.1, Java 21, Maven |
| Frontend | Angular 22 |
| DB | MySQL 9 (dev, `monthley_new`) / MariaDB 11 (prod) |
| Migrasi | Flyway — lihat `cara-kerja.md` §5 untuk laluan manual |
| Prod | `p302_my` (teras), `mpay` (gateway) — DBeaver via VPN |

Backend: `./mb restart` (skrip dalam folder projek, bukan PATH).
`mvn spring-boot:run` **gagal tanpa profil** — datasource bawah `on-profile: dev`.

---

## Keadaan semasa

### Migrasi

V1–V74 dipakai. Flyway berjalan automatik via spring-boot-starter-flyway.
`ddl-auto=validate` — migration & entity mesti selaras atau backend gagal start.

Flyway memiliki skema pada KEDUA-DUA pangkalan data. `monthley_new`
dimigrasi semasa `./mb restart`; `monthley_test` semasa `mvn test`.
Tiada langkah manual.

Terkini:

| Ver | Isi |
|---|---|
| V21 | `anchor_month` INT |
| V22 | Lajur `sp_billing_setting` — BillingContext tidak lagi hardcoded |
| V23–V25 | Rujukan poskod, alamat akaun, medan bil akaun |
| V26 | Jemputan akaun |
| V27 | `subscription.start_date` nullable |
| V28 | Dokumen adjustment (CREDIT_NOTE / DEBIT_NOTE) |
| V29 | `payment.idempotency_key` + UNIQUE — elak double-entry (ADR 0004) |
| V30 | `fi_allocation.debit_document_line_id` — alokasi peringkat line (ADR 0006) |
| V31 | Gugurkan `invoice_grouping` — split ialah binari (ADR 0008) |
| V32 | VIEW `account_balance` — satu takrifan baki (ADR 0009) |
| V33–V37 | VIEW penyata: entri dokumen, padanan alokasi, kepala, baris caj |
| V38 | VIEW `receipt_header` — resit PDF |
| V39–V40 | `payment.remarks` (CASE-008 kes 4) |
| V41 | `document_number_sequence.last_prefix` — reset bila prefix berubah (ADR 0012) |
| V42 | `document_access_token` — pautan awam tanpa log masuk (CASE-006) |
| V43–V44 | VIEW `invoice_header` — invois PDF |
| V45–V46 | `document_payment_status` — PAID / PARTIAL / UNPAID |
| V47 | `document_line_payment_status` — status bayaran peringkat BARIS |
| V48 | `fi_allocation.account_id` nullable — asas invois adhoc |
| V49 | `issued_to_phone` + `remarks` pada dokumen |
| V50 | Akaun `ADHOC-SALES` per SP, dikecualikan daripada kuota |
| V51 | Butiran penerima pada `invoice_header` — invois adhoc |
| V52 | `doc_cancelled` pada baris — batal membebaskan `idem_key` |
| V53–V56 | Peranan pengguna SP, jemputan, tetapan dokumen |
| V57 | `statement_access_token` — penyata awam tanpa log masuk (ADR 0014 P3) |
| V58 | `account_usage_charge` — caj berasaskan penggunaan |
| V59 | `document_line_remarks` — catatan berasingan daripada nama produk |
| V60 | VIEW `sp_ledger_line` — setiap transaksi merentas semua akaun |
| V61 | Gugurkan `account.cached_balance` + `cached_balance_at` — tidak pernah dibaca, semua nilai sifar |
| V62 | `is_platform_owner` + `billing_account_id` pada SP (ADR 0016 peringkat A) |
| V63 | `product.account_limit` + `service_provider.plan_product_id` — kuota berpindah dari `service_plan` ke produk (ADR 0016 peringkat B1) |
| V64 | Gugurkan `service_plan`, `service_plan_id`, `billing_plan` — pelan kini produk sepenuhnya (ADR 0016 peringkat B5) |
| V65 | Akaun GL perbelanjaan: 2000 AP, 5100 Utiliti, 5200 Penyelenggaraan, 5300 Pentadbiran, 5900 Am (ADR 0017) |
| V66 | `ref_module`, `sp_module`, `sp_change_request` — katalog, hak akses, aliran permohonan (ADR 0016) |
| V67 | Skema modul Perbelanjaan: `exp_category`, `exp_supplier`, `exp_invoice`+item, `exp_payment`, `exp_cash_entry`, `exp_setting` + VIEW `exp_invoice_balance` |
| V68 | `source_type` ENUM diperluas: `EXP_INVOICE`, `EXP_PAYMENT`, `EXP_CASH` |
| V69 | `fk_journal_doc` digugurkan — `source_document_id` kini polimorfik, dijaga oleh `JournalSourceInvariantTest` |
| V70 | Kategori perbelanjaan lalai (18 induk, 92 jenis) + akaun GL 5110–5990 sepadan |
| V71 | `exp_payment_method` — kaedah bayaran per SP; transaksi simpan NAMA (snapshot), bukan FK |
| V72 | Akaun bil untuk SP yang didaftar sebelum onboarding satu transaksi (kes yatim ADR 0016) |
| V73 | Skema Aduan: `adu_category`, `adu_complaint`, `adu_reply`, `adu_setting` |
| V74 | Skema Memo: `memo_notice` — satu jadual, tarikh luput per memo |

### Siap

**Teras kewangan**
- Ledger sumber kebenaran; baki diterbitkan `SUM()`, tiada cache
- `AllocationGuard` — invariant + kunci pesimis, satu tempat untuk semua laluan
- Idempotency bayaran manual (ADR 0004) — token klien + UNIQUE constraint
- Adjustment: kredit nota (kurang baki) + debit nota (tambah baki), boleh dibayar
- **Satu takrifan baki + guna advance (ADR 0009 P1-P3)** — baki ialah dokumen
  debit tolak kredit, dilaksana sebagai VIEW `account_balance` dan dikongsi
  LIMA pemanggil yang sebelum ini menyimpang. Baki boleh negatif (kredit).
  Advance di-knock automatik semasa jana bil, dengan posting ledger
  Dr Customer Deposit / Cr AR. Invariant alokasi kini dua sisi (debit + kredit).
- **Alokasi peringkat line (ADR 0006, P1-P6 SELESAI)** — sistem tahu bayaran
  untuk produk mana. Legacy tidak dapat menjawab soalan ini. Backfill
  dijalankan pada SP0002; laporan kutipan ikut produk berkira dengan ledger.

**Enjin bil**
- `fi_period` + `PeriodIds` (fungsi tulen, disahkan lawan 228 baris MO prod)
- `idem_key` UNIQUE STORED GENERATED — idempotency di aras DB
- Proration, pembundaran denominasi, ONE_TIME sekali seumur hidup
- `BillingSettingsPort` — tetapan dibaca dari `sp_billing_setting`

**Dokumen**
- **Penyata akaun (ADR 0010, P1-P5)** — PDF, XLSX dan portal pelanggan
  daripada SATU model. P6 (ambang baris, tak segerak) DITANGGUH selepas
  diukur: 1000 baris dirender dalam 1.2 saat, dua puluh kali ganda saiz
  pengeluaran.
- **Resit PDF** — dua tarikh dibezakan (diterima lawan dikeluarkan),
  kuantiti sebagai kadaran bayaran, catatan kerani
- **Invois PDF** — ringkasan tiga lajur; 'Adjustments' legacy digugurkan
  kerana pelarasan ialah dokumen berasingan
- **Dokumen Kewangan** — cari semua jenis, status bayaran, tapis ikut
  produk pada peringkat BARIS, muat turun CSV, batal, hantar semula
- **Penomboran dokumen (ADR 0012)** — prefix, saiz dan nombor mula
  daripada tetapan SP; reset apabila prefix berubah

**Bayaran**
- Batal resit DAN invois dengan pembalikan penuh — alokasi dilepaskan,
  ledger dibalikkan sebagai contra, token pautan dibatalkan, dan `idem_key`
  DIBEBASKAN supaya kerani boleh jana semula (V52). `active` TIDAK digunakan
  untuk ini: ia bermaksud baris ditarik balik, dan baris tidak aktif hilang
  daripada penyata — dokumen batal mesti kekal kelihatan, ditanda batal.
- **Invois adhoc** — invois kepada bukan pelanggan (caj clamp, jualan
  pameran). Satu akaun `ADHOC-SALES` per SP; FIFO disekat dan lebihan
  ditolak kerana invois di dalamnya milik orang yang tiada kaitan.
- E-mel resit dengan pautan awam tanpa log masuk (CASE-006)

**Laporan (SEPULUH siap)**

Semuanya dengan PDF berkepala SP; kebanyakannya dengan eksport Excel.

| Laporan | Nota |
|---|---|
| Imbangan Duga | UJIAN, bukan laporan: kalau debit tidak sama kredit, setiap laporan lain dibina atas nombor yang salah. Disahkan pada data sebenar — AR sepadan dengan sub-lejar dan lejar SP. |
| Untung Rugi | Hasil daripada bil; perbelanjaan menunggu modul Expenses, dan laporan menyatakannya supaya kosong tidak dibaca sebagai 'tiada perbelanjaan'. |
| Senarai Kutipan | Dua bentuk (ikut resit / ikut alokasi produk). 'Monthly Basis' menapis mengikut tempoh INVOIS — menjawab berapa daripada kutipan untuk bil bulan ini berbanding tunggakan lama. |
| Senarai Akaun | Tujuh lajur, bukan lima belas seperti legacy. Excel mendapat semua medan. |
| Senarai Langganan | Satu baris per langganan. Aktif bermakna status ACTIVE DAN `end_date` belum lepas. |
| Senarai Tunggakan | POTRET pada satu tarikh: dokumen DAN bayaran ditapis sama, jadi laporan yang sama memberi nombor yang sama setiap kali. |
| Ageing | Bucket SEBENAR — jumlah lajur sama dengan total. Legacy menghasilkan bucket yang MELEBIHI jumlah keseluruhan. Susunan boleh diklik. |
| Statistik Bulanan | Kutipan DIPECAH kepada 'tempoh ini' dan 'tunggakan lama'; legacy meletakkannya bersebelahan invois tanpa penjelasan. Carta SVG dikongsi antara skrin dan PDF. |
| Cetak Invois | Pukal, dirender 25 setiap kelompok dan digabung dengan cache cakera. Diukur: 1,441 invois dalam 12 saat. |
| Penyata Pelanggan | Carian akaun melalui modal, tahun yang ADA transaksi sahaja, plus 'Semua sejak mula'. |

**Aplikasi**
- Auth: register, verify, login, forgot/reset password (Resend)
- Settings: Profile, Sales Tax, Localization, Invoice, Receipt, Penalty, Roles
- Akaun, Produk, Jana Bil, Manual Payment, Penyata aras transaksi
- Panel Utama (dashboard SP) — statistik agregat, carta kutipan
- Responsive mobile: portal shell (drawer) + 6 skrin utama

Ujian: **404**, regresi penuh hijau dari `monthley_test` kosong.

#### Bayaran merentas akaun (ADR 0019)

Disahkan pada ToyyibPay pengeluaran, 22 Ogos 2026: dua akaun × RM1, dua
resit tercipta (R26000005 dan R26000006), kedua-dua invois dijelaskan, dan
caj RM1.00 ditolak sekali sahaja.

Satu pepijat ditemui hanya kerana ujian menggunakan wang sebenar:
`receivePayment` menyemak minimum SP di pintu masuknya, jadi setiap
pecahan akaun disemak berasingan. Bayaran RM2 yang memenuhi minimum pada
jumlah gagal apabila dipecahkan kepada RM1 setiap akaun — gerbang menerima
wang, callback melemparkan, dan tiada resit tercipta.

#### Bayaran dalam talian (ADR 0007)

Disahkan hujung ke hujung pada ToyyibPay pengeluaran, 15 Ogos 2026:
bil dicipta, dibayar melalui FPX, callback tiba dalam satu saat, resit
dijana automatik, dan baki invois dikemas kini.

| Bahagian | Nota |
|---|---|
| `GatewayPort` | Kontrak, bukan pelaksanaan — menukar gerbang bermakna satu kelas baharu |
| Kelayakan per SP | AES-GCM, kunci induk dalam persekitaran. Legacy menyimpannya sebagai teks biasa |
| `gateway_txn` | BERASINGAN daripada `payment` — 12% transaksi legacy tidak pernah selesai, dan itu bukan bayaran |
| Callback | Muatan TIDAK dipercayai; kebenaran datang daripada memanggil balik gerbang. ToyyibPay tidak menandatangani callbacknya |
| Skrin pelanggan | Pilih bil, tetapkan amaun (separa atau lebih), bayar, kembali dengan pengesahan |

#### Modul tambahan (ADR 0016, ADR 0017)

| Modul | Skrin | Nota |
|---|---|---|
| **Perbelanjaan** | 8 (Dashboard, Buku Tunai, Pembekal, Invois, Bayaran/PV, Laporan, Kategori, Tetapan) | Posting ledger tiga arah; SST belian masuk akaun belanja (tiada tuntutan input) |
| **Aduan** | 4 (Dashboard, Senarai, Tetapan, Aduan Saya) | Thread balasan; pengadu yang membalas aduan selesai membukanya semula automatik |
| **Memo** | 2 (Memo SP, Memo pelanggan) | Hebahan sehala; tarikh luput per memo, bukan tetapan global |
| **Sumbangan** | — | Belum dibina — bergantung pada gerbang bayaran |

Ketiga-tiganya berkongsi rangka yang sama: katalog `ref_module` ditapis
mengikut sektor SP (`business_types`), hak `sp_module` dipisahkan daripada
bil `account_subscription`, dan aliran permohonan melalui
`sp_change_request`. Menambah modul keempat tidak memerlukan kerja rangka
baharu.

### Sedang berjalan

**Dashboard v2 SIAP** — kedua-dua portal pelanggan dan Panel Utama SP.
Yang tinggal ialah gantung pada ciri yang belum dibina, bukan dashboard
itu sendiri:

| Skrin | Tinggal |
|---|---|
| Portal pelanggan | Butang "Bayar Semua" perlu dikumpulkan MENGIKUT SP — bayaran online terhad kepada satu SP ([ADR 0018](decisions/0018-bayaran-online-satu-sp.md)), dan merentas akaun dalam SP yang sama tidak membenarkan advance ([ADR 0019](decisions/0019-bayaran-merentas-akaun.md)) |
| Portal pelanggan | Muat turun resit dan invois belum berfungsi |
| Portal pelanggan | Butang "Clear" pada carian sejarah |
| Panel Utama SP | "Lihat semua" pada Transaksi Terkini — pautan mati |
| Panel Utama SP | "Laporan" pada Tunggakan — modul Laporan kini wujud; pautan perlu dihalakan ke tab Senarai Tunggakan |
| Panel Utama SP | Data belum disahkan lawan pengeluaran (UI/UX sudah muktamad) |

### Belum hidup

| Kerja | Nota |
|---|---|
| Model yuran (gross/fee/net) | Murah sekarang, mahal selepas ada data online |
| DocumentService semua-atau-tiada | Satu baris wujud gugurkan seluruh invois |
| Laporan: Daily Collection & Bank Recon | Menunggu penyatuan bank |
| Laporan: Tax Summary (SST) | Menunggu keputusan cukai |
| `account_limit` paparan sahaja | Kuota akaun dipaparkan pada tiga skrin (Settings, senarai SP, onboarding) tetapi TIDAK dikuatkuasakan di mana-mana — tiada apa yang menghalang akaun ke-301 pada Pakej 300. Ditemui semasa ADR 0016 peringkat B1 |
| Sahkan bayaran adhoc hujung-ke-hujung | Tab Search Invoice belum diuji dengan data sebenar |
| i18n | Label UI bercampur BM dan Inggeris (soalan 25) |
| **Late penalty — enjin** | Tetapan LENGKAP wujud (`sp_penalty_setting`: jenis FIXED/PERCENT, amaun, `penalty_after_day`, taxable, compounded) dan skrin Settings menyimpannya. Tetapi TIADA pengiraan di mana-mana — grep penalty dalam modul billing = kosong, dan `PENALTY` bukan jenis dokumen. SP boleh mengisi tetapan dan menekan Simpan, dan tiada apa berlaku. Perlu kajian aliran dahulu: bila penalti dikira (jana bil? kerja harian?), ia dokumen berasingan atau baris pada invois, dan `compounded` bermakna atas baki atau atas penalti sebelumnya |
| Access card | Fasa 2 — aliran belum dikaji |
| Auto-jana bil ikut `invoice_gen_day` | Penjadual tiada — jana manual (Alat -> Jana Bil) dan invois tunggal berfungsi. Gate hari dilaksana BERSAMA penjadual, bukan sebelum ([ADR 0008](decisions/0008-split-invoice-and-gen-day.md) pencetus #1) |
| UI tukar pelan | Backend siap (`sp_change_request` PLAN_CHANGE + `ModuleEntitlementService.changePlan`), UI belum. Tempatnya ialah skrin Edit SP |
| Storan fail | Monthley tiada storan fail langsung — CSV caj penggunaan dihurai lalu dibuang. Gambar sokongan aduan ditangguh sehingga keputusan dibuat (cakera VPS vs storan objek); ia menjejaskan modul lain juga |
| Skrin tetapan gerbang | Endpoint wujud (`/api/v1/platform/gateway`, superadmin sahaja) tetapi tiada UI. Kelayakan kini dimasukkan melalui API |
| MonthleyPay ialah gerbang SEBENAR | ToyyibPay digunakan untuk mengesahkan aliran, bukan sebagai gerbang pengeluaran. Model MonthleyPay: SATU Seller ID FPX milik Rapidevelop (`SE00053268`), setiap SP sebagai merchant di bawahnya dengan kunci sendiri, dan wang masuk ke Rapidevelop sebelum diagihkan. `GatewayPort` menjadikan pertukaran itu satu kelas baharu — yang diperlukan hanyalah algoritma checksum MonthleyPay daripada pasukan yang menyelenggaranya |
| Notifikasi bayaran kepada SP | Legacy menghantar e-mel kepada SP setiap kali bayaran online diterima. Belum ada dalam sistem baharu. Perlu diputuskan: ke `contact_email`, `helpdesk_email`, atau kedua-dua; dan sama ada SP boleh mematikannya (sepuluh bayaran sehari bermakna sepuluh e-mel) |
| Reconciliation harian gerbang | ADR 0007 #4 — banding setiap bayaran gerbang berjaya dengan resit, dan beri amaran bila ada bayaran tanpa resit, resit tanpa bayaran, atau amaun tak padan. Dalam legacy, 12% transaksi tidak pernah selesai; tanpa reconciliation, tiada cara membezakan 'pengguna batal' daripada 'bayaran berjaya tetapi callback gagal' |
| MonthleyPay sebagai gerbang | `GatewayPort` ialah kontrak; ToyyibPay pelaksanaan pertama. MonthleyPay memerlukan algoritma checksumnya, yang hanya diketahui oleh pasukan yang menyelenggaranya |
| Sahkan domain monthley.my dalam Resend | E-mel kini dihantar dari `monthley@perantau.org.my` kerana `monthley.my` belum disahkan. Berfungsi, tetapi domain sendiri lebih betul |
| `FifoAllocator` label "deposit" | Lebihan selepas semua invois dilunaskan dinamakan `deposit`; istilah sistem ialah **advance**. Deposit ialah wang jaminan yang dipulangkan — perkara berbeza sepenuhnya. Menamakan semula sahaja, tiada perubahan logik |

---

## Isu legacy yang kita elak

Setiap kes di bawah ialah **kejadian sebenar dalam production legacy**,
disiasat dari data. Guard yang disenaraikan wujud khusus untuk menghalangnya
berulang. Baca sebelum menyentuh laluan berkaitan.

| Kes | Apa berlaku | Guard dalam sistem baru |
|---|---|---|
| [CASE-001](evidence/CASE-001-balance-mismatch-A0124.md) | Baki menyimpang — alokasi yatim dari resit dibatalkan yang tidak pernah dibalikkan | Ledger sumber kebenaran; `AllocationGuard` (invariant + kunci pesimis di SATU tempat, bukan disalin per laluan) |
| [CASE-002](evidence/CASE-002-amt_actv-scenario-catalog.md) | Baki disimpan di **empat tempat** (`bal_amt`, `amt_actv`, running balance, doc_link) yang menyimpang antara satu sama lain | Satu event ledger; semua baki diterbitkan on-read |
| [CASE-003](evidence/CASE-003-online-payment-integration.md) | 33 anomali / 20,885 resit online. Terburuk **RM310,000 untuk bayaran RM310**. Satu kes terlepas 5 bulan | [ADR 0007](decisions/0007-online-payment-guards.md) — amaun dari gateway (jangan kira), callback stateless, idempotency, reconciliation harian |
| [CASE-004](evidence/CASE-004-ledger-line-taxonomy.md) | Jenis baris ledger tersembunyi dalam teks bebas — `prod_descr` mengandungi dua ejaan untuk konsep sama ("Advanced"/"Advance Payment"), tempoh ditanam dalam string, produk dinamakan semula memutuskan padanan | `line_type` eksplisit + `product_id`; `period_start`/`period_end` sebagai DATE; teks jadi snapshot papar, tidak pernah disoal |
| CASE-005 | `/accounts/my` mengira baki dengan formula sendiri (invois tolak alokasi) — buta kepada kredit belum dipadankan. M04 dipaparkan berhutang RM200 LEBIH daripada sebenar; kredit RM38.41 M06 dipaparkan sebagai sifar | VIEW `account_balance` di SETIAP pemanggil; tunggakan dan baki dinamakan berasingan; `MyAccountsBalanceTest` mengunci perbezaan kedua-dua formula |
| [CASE-006](evidence/CASE-006-aliran-emel-penyata.md) | Aliran e-mel legacy mencipta satu dokumen `P` hantu setiap penyata semata-mata untuk memegang UUID pautan — 51 rekod bukan-kewangan pada satu akaun sejak 2023 | Jadual token berasingan (ADR 0011); jangan letak bukan-dokumen dalam jadual dokumen |
| [CASE-007](evidence/CASE-007-langganan-bertindih.md) | Dua langganan produk SAMA bertindih pada akaun sama dicaj penuh kedua-duanya dalam satu larian; tiada prorata. `uk_subscr` dan `idem_key` kedua-duanya membenarkannya | Guard `ACTIVE` pada dua laluan cipta; prorata disahkan BUKAN pepijat (`account.start_date` NULL mematikannya dengan sengaja, billing-rules §6) |
| [CASE-008](evidence/CASE-008-tetapan-tidak-dikuatkuasakan.md) | Tetapan dikumpul dalam UI tetapi diabaikan senyap oleh backend — ENAM kes dalam dua hari, termasuk tarikh bayaran dan pemilihan invois | 2/6 dibetulkan; setiap tetapan perlukan ujian penguatkuasaan |

### Empat keluarga hanyutan (CASE-001)

1. Over-allocation tanpa pengesahan semasa write + race
2. Kunci per-laluan — satu laluan ingat, satu lupa
3. Dokumen boleh-ubah
4. Double-submit

Ketiga-tiga guard utama (`AllocationGuard`, idempotency, alokasi peringkat
line) menyasar keluarga-keluarga ini.

### Keputusan (ADR)

| # | Keputusan | Status |
|---|---|---|
| [0001](decisions/0001-boot4-modular-starters.md) | Boot 4 modular starters | Diterima |
| [0002](decisions/0002-statement-aras-txn.md) | Penyata aras transaksi | Diterima |
| [0003](decisions/0003-account-adjustment.md) | Account adjustment | Diterima |
| [0004](decisions/0004-manual-payment-idempotency.md) | Idempotency bayaran manual | Dilaksana |
| [0005](decisions/0005-line-level-allocation.md) | Alokasi peringkat line — catatan isu | Digantikan 0006 |
| [0006](decisions/0006-line-level-allocation-plan.md) | Alokasi peringkat line — rancangan | P1–P6 dilaksana |
| [0007](decisions/0007-online-payment-guards.md) | Guard payment online | Dilaksana — disahkan pada ToyyibPay pengeluaran. Reconciliation harian belum |
| [0008](decisions/0008-split-invoice-and-gen-day.md) | Split invois ialah binari | Digantikan sebahagian oleh 0011 |
| [0009](decisions/0009-baki-tunggal-dan-advance.md) | Satu takrifan baki + guna advance | P1–P3 dilaksana |
| [0010](decisions/0010-penyata-akaun.md) | Penyata akaun | P1–P5 siap; P6 ditangguh selepas diukur |
| [0011](decisions/0011-split-ikut-tempoh.md) | Split ikut TEMPOH, bukan produk sahaja | Dilaksana |
| [0012](decisions/0012-penomboran-dokumen.md) | Penomboran baca tetapan SP | Dilaksana |
| [0013](decisions/0013-anjakan-mod-aras-kasar.md) | Anjakan mod aras kasar | Dilaksana |
| [0014](decisions/0014-penghantaran-emel-pukal.md) | Penghantaran emel pukal | Dilaksana (`email_outbox` + dispatcher) |
| [0016](decisions/0016-modul-tambahan-dan-langganan-sp.md) | Modul tambahan &amp; langganan SP | Dilaksana — katalog, penapis sektor, hak/bil dipisahkan, aliran permohonan |
| [0017](decisions/0017-satu-database-untuk-semua-modul.md) | Satu database untuk semua modul | Dilaksana — Perbelanjaan, Aduan, Memo |
| [0018](decisions/0018-bayaran-online-satu-sp.md) | Bayaran online terhad kepada satu SP | Diterima — UI belum dikumpulkan mengikut SP |
| [0019](decisions/0019-bayaran-merentas-akaun.md) | Bayaran merentas akaun (SP sama) | Diterima — dalam pembinaan |

Tiada ADR 0015. Nombor itu pernah dirujuk untuk gerbang bayaran, tetapi
keputusannya sebenarnya ada dalam ADR 0007 — rujukan yang salah telah
dibuang.

---

## Model teras — ringkasan

Empat paksi bebas menentukan setiap caj:

| Paksi | Sumber | Kesan |
|---|---|---|
| **Mod** | `sp_billing_setting.gen_mode` | Anjak period asas: POST −1, CUR 0, PRE +1 |
| **Ufuk** | `account.charge_frequency` | Berapa kitaran ditarik satu larian |
| **Aras** | `product.charge_frequency` | Granulariti `period_id` setiap caj |
| **Anchor** | `product.anchor_month` | Bulan kitaran bermula (null = Jan) |

Dua aras period:

- `financial_document.period_id` — period **larian** (aras = freq akaun)
- `financial_document_line.period_id` — period **liputan** (aras = freq produk)
`fi_period` = jadual **rujukan** sahaja. Enjin guna `PeriodIds` (fungsi tulen).

Butiran penuh: [`domain/billing-rules.md`](domain/billing-rules.md)

---

## Prinsip yang tidak berubah

1. **Ledger sumber kebenaran.** Tiada cache `bal_amt`. Baki diterbitkan dengan `SUM()`.
2. **Stateless atas stateful.** Tiada penunjuk boleh-ubah (`last_charge`, `last_gen_dt`). Aritmetik modulo + soalan ledger.
3. **Invariant dalam domain, bukan disiplin caller.** Kekangan DB > semakan aplikasi.
4. **Jangan teka, baca.** Query prod, `cat` fail, DevTools. Lihat `cara-kerja.md` §2.
5. **Setiap peraturan ada bukti.** Setiap keputusan ada sebab bertulis.
6. **Ambil fakta, jangan kira semula.** Bayaran ialah fakta luaran; jangan terbitkan semula dari keadaan dalaman (CASE-003).

---

## Soalan terbuka

| # | Soalan | Menyekat |
|---|---|---|
| 1 | `FiPeriodService.getIntertwinedPeriods()` belum dibaca — **teras enjin** | `PeriodResolver` penuh |
| 2 | `charge_1st_mon` dalam `mon_sp_prod` — anchor yang tidak siap? | Reka bentuk anchor |
| 3 | Pakej: caj mengalir bagaimana dari parent ke anak? | Modul subscription |
| 4 | Penyata PDF — bawa ke hadapan, penyata ikut tahun, kepala SP | [ADR 0010](decisions/0010-penyata-akaun.md) — P1 lulus |
| 5 | Jenis `Money` — penuh (46 fail) atau bersasar (sempadan gateway)? | Modul online |
| 6 | Kes E CASE-003 (~0.1%) — hipotesis: amaun asas bocor dari txn serentak. Boleh diuji | Pengesahan punca |
| 7 | Query duplicate J00 merentas 71 SP — belum dijalankan | Skop CASE-001 |
| 8 | ~~`application-test.yml` menunjuk ke `monthley_new`~~ **SELESAI 28 Julai 2026** — ujian kini guna `monthley_test`. Cipta semula dengan `./mb testdb` (37 migrasi dari kosong). Backend boleh hidup semasa `mvn test` | ✓ |
| 12 | ~~Prorata tidak dikenakan pada langganan pertengahan bulan~~ **DITARIK BALIK** (e3e207e) — dakwaan ditulis tanpa membaca kod. `InvoiceCalculator` menjadikan prorata bergantung pada `account.start_date`, NULL dengan sengaja (billing-rules §6). Baris dikekalkan supaya jurang nombor tidak disiasat semula | ✓ |
| 13 | Checkbox pilihan invois pada Manual Payment dihantar tetapi DIABAIKAN apabila `allow_selective = 0` — kerani tanda enam invois, sistem bayar yang ketujuh tanpa amaran (ADR 0011) | UI hormati tetapan |
| 14 | `sp_document_setting.selective_payment` tiada siapa membacanya; `service_provider.allow_selective` yang digunakan. Dua lajur satu konsep | Gugurkan yang mati |
| 15 | `allowSelective` menelan RuntimeException dan mengembalikan false — kegagalan query mematikan pemilihan secara senyap | Log, jangan telan |
| 17 | ~~**Penomboran dokumen** tidak membaca `sp_document_setting`~~ **SELESAI 29 Julai** — ADR 0012 dilaksana; prefix, saiz dan nombor mula daripada tetapan, reset bila prefix berubah | ✓ |
| 18 | ~~`enable_manual_payment` tidak dikuatkuasakan~~ **SELESAI 28 Julai** — semakan dalam ManualPaymentController, diuji hidup/mati | ✓ |
| 19 | ~~`remarks` dibuang~~ **SELESAI 28 Julai** — V39 `payment.remarks`, dipaparkan pada resit PDF | ✓ |
| 20 | Pautan resit e-mel guna `monthley.app-url` (port 4200 dalam pembangunan). Berfungsi kerana proxy Angular memajukan `/api/**`; dalam pengeluaran backend mesti berada di belakang proxy yang sama, ATAU laluan Angular `/resit/{token}` diperlukan | Semak semasa deploy |
| 21 | `IllegalArgumentException` digunakan untuk DUA perkara: validasi input (`accountId diperlukan`) dan keadaan mustahil (`Dokumen tak wujud`, `anchor_month mesti 1-12`). Yang pertama patut 400, yang kedua ialah pepijat dan patut kekal 500 | Pengecualian berasingan untuk validasi |
| 22 | ~~UI tidak menyemak `enable_manual_payment`~~ **SELESAI 30 Julai** — amaran dan butang terkunci dalam skrin bayaran | ✓ |
| 25 | **Label UI bercampur BM dan Inggeris** — 'List of Transaction' bersama 'Kod Produk'; 'Issued To' bersama 'No. Dokumen'; 'Search'/'Clear' bersama 'Tutup'. `sp_billing_setting.language` hanya digunakan dalam PDF; UI tiada mekanisme bahasa dan setiap label ditulis keras. Toggle EN/BM pada bar atas disyaki tidak berfungsi | Satu blok kerja i18n, bukan skrin demi skrin — membetulkan sambil membina bermakna menulis semula bila mekanisme masuk |
| 26 | `DocumentPort.cancelDocument` TIDAK melepaskan alokasi mahupun membalikkan ledger — dokumentasi port menyatakan pemanggil yang bertanggungjawab. `PaymentService` melakukannya betul; tiada apa menghalang pemanggil baharu memanggil port terus dan meninggalkan alokasi yatim (keluarga hanyutan CASE-001 #1). Overload satu-argumen yang membuang jejak audit sudah dibuang (9a21c19), tetapi itu isu berbeza | Namakan `markCancelledOnly`, atau pindahkan pelepasan alokasi ke dalam |
| 27 | ~~Endpoint cancel tiada ujian~~ **SELESAI 30 Julai** — `CancelEndpointTest`, 9 ujian. Disahkan dengan ujian mutasi: menanggalkan `requireRole` memerahkan dua ujian | ✓ |
| 28 | **Sekatan modul ikut pakej** belum ada. CORAK DIPUTUSKAN 30 Julai: **benarkan masuk, sekat transaksi** — SP boleh membuka skrin dan melihat apa yang ditawarkan, tetapi tindakan yang mengubah data ditolak sehingga dilanggan. Manual Payment ialah rujukan corak (amaran + butang terkunci, backend tetap menguatkuasakan). Menyembunyikan menu ialah jualan yang hilang dan menu yang lenyap secara misteri bila pakej berubah. `sp_module_access` sebagai kebenaran sebenar, pakej sebagai template. **REKA BENTUK PENUH 10 Ogos: [ADR 0016](decisions/0016-modul-tambahan-dan-langganan-sp.md)** — jadual dinamakan `sp_module`, guard pada endpoint tulis sahaja | Skema belum dibina |
| 23 | ~~**`cancelDocument` tidak membalikkan alokasi**~~ **SELESAI 30 Julai** — `cancelInvoice` melepaskan alokasi dan membalikkan ledger sebagai contra; `cancelReceipt` kini merekod sebab dan membatalkan token. Tiada ADR diperlukan: baki DITERBITKAN daripada dokumen (ADR 0009), jadi ia betul automatik | ✓ |
| 24 | ~~Tiada penulis PDF **invois**~~ **SELESAI 29 Julai** — V43/V44 `invoice_header`, templat, penulis, endpoint. Nota kredit/debit masih menggunakan templat invois; nota kredit belum ada bentuk sendiri | ✓ separa |
| 16 | ~~`PaymentResult.receiptId()` menyesatkan~~ **SELESAI 28 Julai** — kini `paymentId` dan `receiptDocumentId`, dua medan untuk dua maksud | ✓ |
| 9 | Frontend portal masih membaca `balance` sahaja; medan `arrears` baharu belum dipapar. Selepas deploy, baki negatif muncul di tempat tunggakan dahulu berada | Kemas kini portal UI |
| 10 | Adakah versi lama `/accounts/my` pernah tersiar kepada pelanggan produksi? Jika ya, siapa nampak nombor salah | Semakan produksi |
| 11 | `idem_key` guna `period_start`, bukan `period_id` — dua langganan menghasilkan kunci berbeza dalam tempoh sama. Guard aplikasi kini menghalangnya, jadi ini pertahanan mendalam yang belum diperlukan (CASE-007) | Tukar hanya jika guard pernah gagal |

---

## Fasa 2 (bukan sekarang)

- Penalti lewat — penambahbaikan (legacy: baris usage `txn_code = M1500`)
- Payment Form, Settlement, Aduan, Memo
- Vertikal: JMB (pengurusan pelawat), Pendidikan (pelajar/guru)
- Lesen bermeter (`lic_id`, `qty_used`)

---

## Senarai tindakan aktif

Dari [`domain/legacy-generator-analysis.md`](domain/legacy-generator-analysis.md) §7:

**Skema**
- [ ] `currency.smallest_denomination` + `sp_billing_setting.effective_smallest_denomination`
- [ ] `transaction_code` jadual rujukan
- [x] Gugurkan `account.cached_balance` + `cached_balance_at` (V61)
- [x] `once_only` (V18)
- [x] `sp_billing_setting` lajur (V22)

**Enjin**
- [x] Tapis `parent_subscription_id IS NULL`
- [x] `ONE_TIME`: caj sekali seumur hidup, tiada proration
- [x] Pembundaran denominasi terkecil (per baris, CEILING)
- [x] Gate price override aras SP
- [x] Proration exclude ikut bulan
- [x] Alokasi peringkat line (ADR 0006 P1–P6)
- [x] `PER_USE`: caj berasaskan penggunaan (V58/V59, skrin Alat) — muat
      naik Excel, pratonton, ditanda INVOICED semasa jana bil
- [x] Auto-knock advance semasa jana invois (ADR 0009 P3)
- [x] P1 spike: `counter(pages)` openhtmltopdf disahkan (ADR 0010)
- [x] P2 `StatementService` + `StatementModel` + invarian (ADR 0010)
- [x] P3 penulis PDF + sub-baris padanan (ADR 0010)
- [x] P4a `/accounts/my` guna `account_balance` (CASE-005)
- [x] P4b tiga endpoint penyata + ujian pemilikan & penyewa (ADR 0010)
- [x] Penyata akaun: penulis XLSX (`StatementXlsxWriter`, 4c50684)
- [ ] Penyata akaun: ambang baris (ADR 0010 P6 — DITANGGUH selepas diukur)
- [x] Aliran e-mel + pautan awam resit/invois/penyata (ADR 0011, 0014 P3-P4)
- [ ] Butang Pay pada halaman awam — bergantung pada gerbang bayaran (ADR 0015)
- [ ] Notifikasi adjustment kepada admin SP lain — legacy menghantarnya
      ("adjustments have been made to account D0716 by FARA AZWA due to
      WATER 12 AUG 2022 - 12 NOV 2022"). Adjustment ialah kerani menukar
      baki tanpa duit bergerak; admin lain perlu tahu ia berlaku
- [ ] Kumpul ralat per akaun; jangan `break`

**Jangan tiru**
- [ ] `rate²`, presisi `double`, qty tak selaras amaun
- [ ] `ref_no` ikut kiraan
- [ ] `break` pada exception
- [ ] Snapshot baki berjalan
- [ ] Amaun resit dikira dari baki invois (CASE-003 Kes D)
- [ ] State dikongsi dalam handler serentak (CASE-003 Kes A/B/C)
