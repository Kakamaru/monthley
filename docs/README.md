# Monthley — Dokumentasi

> **Mula di sini.** Dokumen ini indeks + keadaan semasa.
> Kemas kini: 25 Julai 2026

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

V1–V32 dipakai. Flyway berjalan automatik via spring-boot-starter-flyway.
`ddl-auto=validate` — migration & entity mesti selaras atau backend gagal start.

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
- **Alokasi peringkat line (ADR 0006, P1-P7 SELESAI)** — sistem tahu bayaran
  untuk produk mana. Legacy tidak dapat menjawab soalan ini. Backfill
  dijalankan pada SP0002; laporan kutipan ikut produk berkira dengan ledger.

**Enjin bil**
- `fi_period` + `PeriodIds` (fungsi tulen, disahkan lawan 228 baris MO prod)
- `idem_key` UNIQUE STORED GENERATED — idempotency di aras DB
- Proration, pembundaran denominasi, ONE_TIME sekali seumur hidup
- `BillingSettingsPort` — tetapan dibaca dari `sp_billing_setting`

**Aplikasi**
- Auth: register, verify, login, forgot/reset password (Resend)
- Settings: Profile, Sales Tax, Localization, Invoice, Receipt, Penalty, Roles
- Akaun, Produk, Jana Bil, Manual Payment, Penyata aras transaksi
- Panel Utama (dashboard SP) — statistik agregat, carta kutipan
- Responsive mobile: portal shell (drawer) + 6 skrin utama

Ujian: 18 kelas, regresi penuh hijau.

### Sedang berjalan

- **Dashboard v2 (frontend).** Reka bentuk baharu: kad Terkumpul dengan
  sasaran, donut kadar bayar, Invois vs Kutipan (bar berganda), Kutipan Ikut
  Produk (donut). **Semua endpoint sudah sedia** — `summary` (dengan sasaran,
  kadar bayar, MoM), `invoice-vs-collection` (3 siri), `collection-by-product`.
  Tinggal frontend.

### Belum hidup (mula di sini sesi depan)

| Kerja | Nota |
|---|---|
| **Payment gateway (online)** | **BELUM DIBINA.** Guard reka bentuk sudah diputuskan — [ADR 0007](decisions/0007-online-payment-guards.md). Bina ikut guard tersebut, bukan tampal kemudian. |

| Model yuran (gross/fee/net) | Murah sekarang, mahal selepas ada data online |
| `cached_balance` lajur mati | Diisytihar dalam entity, tidak dibaca/ditulis. Perlu digugurkan. |
| DocumentService semua-atau-tiada | Satu baris wujud gugurkan seluruh invois |
| PER_USE, kumpul ralat per akaun | Belum |

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
| [0006](decisions/0006-line-level-allocation-plan.md) | Alokasi peringkat line — rancangan | P1–P6 siap, P7 tinggal |
| [0007](decisions/0007-online-payment-guards.md) | Guard payment online | Kekangan; modul belum dibina |

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
| 8 | **DISAHKAN 26 Julai 2026** — `application-test.yml` menunjuk ke `monthley_new`, DB sama dengan backend pembangunan. Dengan backend hidup, konteks Spring GAGAL naik (`Unable to determine Dialect`); `kill` proses backend → ujian lulus serta-merta. Ini punca `mvn test` gagal-lalu-lulus, dan ia jenis kegagalan yang mengajar orang abaikan hasil merah | DB ujian berasingan — bukan lagi pilihan |
| 13 | Checkbox pilihan invois pada Manual Payment dihantar tetapi DIABAIKAN apabila `allow_selective = 0` — kerani tanda enam invois, sistem bayar yang ketujuh tanpa amaran (ADR 0011) | UI hormati tetapan |
| 14 | `sp_document_setting.selective_payment` tiada siapa membacanya; `service_provider.allow_selective` yang digunakan. Dua lajur satu konsep | Gugurkan yang mati |
| 15 | `allowSelective` menelan RuntimeException dan mengembalikan false — kegagalan query mematikan pemilihan secara senyap | Log, jangan telan |
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
- [ ] Gugurkan `account.cached_balance` (lajur mati)
- [x] `once_only` (V18)
- [x] `sp_billing_setting` lajur (V22)

**Enjin**
- [x] Tapis `parent_subscription_id IS NULL`
- [x] `ONE_TIME`: caj sekali seumur hidup, tiada proration
- [x] Pembundaran denominasi terkecil (per baris, CEILING)
- [x] Gate price override aras SP
- [x] Proration exclude ikut bulan
- [x] Alokasi peringkat line (ADR 0006 P1–P6)
- [ ] `PER_USE`: sapu usage PENDING, tanda DONE
- [x] Auto-knock advance semasa jana invois (ADR 0009 P3)
- [x] P1 spike: `counter(pages)` openhtmltopdf disahkan (ADR 0010)
- [x] P2 `StatementService` + `StatementModel` + invarian (ADR 0010)
- [x] P3 penulis PDF + sub-baris padanan (ADR 0010)
- [x] P4a `/accounts/my` guna `account_balance` (CASE-005)
- [x] P4b tiga endpoint penyata + ujian pemilikan & penyewa (ADR 0010)
- [ ] Penyata akaun: penulis XLSX, ambang baris (ADR 0010 P5-P6)
- [ ] Aliran e-mel + pautan awam + butang Pay (ADR 0011, CASE-006)
- [ ] Kumpul ralat per akaun; jangan `break`

**Jangan tiru**
- [ ] `rate²`, presisi `double`, qty tak selaras amaun
- [ ] `ref_no` ikut kiraan
- [ ] `break` pada exception
- [ ] Snapshot baki berjalan
- [ ] Amaun resit dikira dari baki invois (CASE-003 Kes D)
- [ ] State dikongsi dalam handler serentak (CASE-003 Kes A/B/C)
