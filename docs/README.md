# Monthley — Dokumentasi

> **Mula di sini.** Dokumen ini indeks + keadaan semasa.
> Kemas kini: 23 Julai 2026

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

V1–V30 dipakai. Flyway berjalan automatik via spring-boot-starter-flyway.
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

### Siap

**Teras kewangan**
- Ledger sumber kebenaran; baki diterbitkan `SUM()`, tiada cache
- `AllocationGuard` — invariant + kunci pesimis, satu tempat untuk semua laluan
- Idempotency bayaran manual (ADR 0004) — token klien + UNIQUE constraint
- Adjustment: kredit nota (kurang baki) + debit nota (tambah baki), boleh dibayar
- **Alokasi peringkat line (ADR 0006)** — sistem tahu bayaran untuk produk mana.
  Legacy tidak dapat menjawab soalan ini.

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

- **P7 — laporan line-level.** Query "Kutipan Ikut Produk" perlu ditukar dari
  `JOIN ON debit_document_id = line.document_id` (berganda) kepada
  `JOIN ON debit_document_line_id = line.id` (tepat). Endpoint dashboard v2
  ditangguh sehingga ini siap.

### Belum hidup (mula di sini sesi depan)

| Kerja | Nota |
|---|---|
| **Payment gateway (online)** | **BELUM DIBINA.** Guard reka bentuk sudah diputuskan — [ADR 0007](decisions/0007-online-payment-guards.md). Bina ikut guard tersebut, bukan tampal kemudian. |
| **Guna-advance** | Advance tercipta tetapi tiada kod yang memakainya. Legacy knock advance masa jana bil; baki boleh negatif. Perlu ADR 0008. |
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
| 4 | Bila advance dipakai — masa jana bil, masa bayar seterusnya, atau job? | ADR 0008 |
| 5 | Jenis `Money` — penuh (46 fail) atau bersasar (sempadan gateway)? | Modul online |
| 6 | Kes E CASE-003 (~0.1%) — hipotesis: amaun asas bocor dari txn serentak. Boleh diuji | Pengesahan punca |
| 7 | Query duplicate J00 merentas 71 SP — belum dijalankan | Skop CASE-001 |

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
- [ ] Auto-knock advance semasa jana invois (ADR 0008)
- [ ] Kumpul ralat per akaun; jangan `break`

**Jangan tiru**
- [ ] `rate²`, presisi `double`, qty tak selaras amaun
- [ ] `ref_no` ikut kiraan
- [ ] `break` pada exception
- [ ] Snapshot baki berjalan
- [ ] Amaun resit dikira dari baki invois (CASE-003 Kes D)
- [ ] State dikongsi dalam handler serentak (CASE-003 Kes A/B/C)
