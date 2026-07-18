# Monthley — Dokumentasi

> **Mula di sini.** Dokumen ini indeks + keadaan semasa.
> Kemas kini: 17 Julai 2026

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

V1–V20 dipakai — disahkan hujung-ke-hujung dari DB kosong (18 Julai 2026).
75 ujian hijau. Flyway berjalan automatik via spring-boot-starter-flyway.

Terkini:

| Ver | Isi |
|---|---|
| V16 | `fi_period` (361 baris, 2017–2035); `invoice_exclude_period` dari `varchar(7)` ke `period_id BIGINT` |
| V17 | `financial_document_line.period_id` + FK; gugurkan `account_subscription.last_charged_at`; gugurkan `product.generation_day` |
| V18 | `once_only` + `idem_key` bercabang — ONE_TIME sekali seumur hidup |
| V19 | `financial_document.period_id` dialih dari `accounting_period` ke `fi_period`; `period` varchar digugurkan |
| V20 | `financial_document_line.proration_ratio` — ratio eksplisit 8 t.p., bukan dibakar ke quantity |

### Siap

- Skeleton Spring Boot boot bersih
- Domain: `doc_type` (INV/RCP/J00/J01/J02/JNL), `invoice_grouping`, `module_catalog` + `sp_module`
- `fi_period` + `PeriodIds` (fungsi tulen, disahkan lawan 228 baris MO prod)
- `idem_key` UNIQUE STORED GENERATED — idempotency di aras DB
- Auth: register, verify, login, forgot/reset password (Resend)
- Settings: Profile, Sales Tax, Localization, Invoice, Receipt, Penalty

### Belum hidup (mula di sini sesi depan)

- **BillingContext hardcoded** — allowPriceOverride/termDays/minDenom/excludedPeriodIds
  perlu dari sp_billing_setting. Perlu V21 (lajur hilang) + tenancy::api port +
  BillingSettingsPort. INI YANG MENYEKAT tab exclude period.
- **cached_balance lajur mati** — declared dlm entity, tak dibaca/ditulis. V21 gugurkan.
- **DocumentService semua-atau-tiada** — satu baris wujud gugurkan seluruh invois.
- **Belum**: auto-knock kredit, PER_USE, kumpul ralat per akaun, penalti (fasa 2).

### Sedang berjalan

- **Tab Invoice Period Exclude** — skema sedia (`period_id` ada), `SettingsController` + dropdown Angular belum
- **`PeriodResolver` + `InvoiceCalculator`** — perlu ditulis semula untuk aras produk, anchor, tarikh langganan, harga berkesan

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

```
period_id = tahun×1000000 + H×100000 + Q×10000 + bulan×100
```

`fi_period` = jadual **rujukan** sahaja. Enjin guna `PeriodIds` (fungsi tulen).

Butiran penuh: [`domain/billing-rules.md`](domain/billing-rules.md)

---

## Prinsip yang tidak berubah

1. **Ledger sumber kebenaran.** Tiada cache `bal_amt`. Baki diterbitkan dengan `SUM()`.
2. **Stateless atas stateful.** Tiada penunjuk boleh-ubah (`last_charge`, `last_gen_dt`). Aritmetik modulo + soalan ledger.
3. **Invariant dalam domain, bukan disiplin caller.** Kekangan DB > semakan aplikasi.
4. **Jangan teka, baca.** Query prod, `cat` fail, DevTools. Lihat `cara-kerja.md` §2.
5. **Setiap peraturan ada bukti.** Setiap keputusan ada sebab bertulis.

---

## Soalan terbuka

| # | Soalan | Menyekat |
|---|---|---|
| 1 | `FiPeriodService.getIntertwinedPeriods()` belum dibaca — **teras enjin** | `PeriodResolver` penuh |
| 2 | `charge_1st_mon` dalam `mon_sp_prod` — anchor yang tidak siap? | Reka bentuk anchor |
| 3 | Pakej: caj mengalir bagaimana dari parent ke anak? | Modul subscription |
| 4 | `lic_id`, `qty_used` — lesen bermeter | Fasa 2 |
| 5 | `invoice_gen_day` — gate untuk CURRENT sahaja, atau semua mod? | Penjadual |
| 6 | Senarai penuh `txn_code` (M1000, M1500 diketahui) | Modul usage |
| 7 | Ujian prod 17 Julai tinggalkan 1 akaun + 13 invois dalam SP `MY000060`. Contra `J01`? | Kebersihan prod |
| 8 | Query duplicate J00 merentas 71 SP — belum dijalankan | Skop CASE-001 |

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
- [ ] `sp_billing_setting.allow_price_override BOOLEAN`
- [ ] `currency.smallest_denomination` + `sp_billing_setting.effective_smallest_denomination`
- [ ] `transaction_code` jadual rujukan
- [x] `once_only` (V18)

**Enjin**
- [x] Tapis `parent_subscription_id IS NULL`
- [x] `ONE_TIME`: caj sekali seumur hidup, period = tahun semasa, tiada proration
- [ ] `PER_USE`: sapu usage PENDING, tanda DONE
- [ ] Auto-knock kredit semasa jana invois + invariant allocation
- [x] Pembundaran denominasi terkecil (per baris, CEILING)
- [x] Gate price override aras SP
- [x] Proration exclude ikut bulan (sentiasa, tidak kira bendera `prorated`)
- [ ] Kumpul ralat per akaun; jangan `break`

**Jangan tiru**
- [ ] `rate²`, presisi `double`, qty tak selaras amaun
- [ ] `ref_no` ikut kiraan
- [ ] `break` pada exception
- [ ] Snapshot baki berjalan
