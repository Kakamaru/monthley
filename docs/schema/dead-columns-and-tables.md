# Lajur & Jadual Mati / Simpanan

> Kemas kini: 18 Julai 2026
> **Baca ini sebelum membina apa-apa atas skema.** Beberapa table wujud tetapi
> tidak pernah digunakan. Mudah tersilap anggap ia sebahagian reka bentuk aktif.

---

## `accounting_period` — dicipta, tidak pernah digunakan

```sql
-- V1__init_schema.sql
CREATE TABLE accounting_period (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code    VARCHAR(20) NOT NULL,
  period     VARCHAR(7)  NOT NULL,        -- 'YYYY-MM'
  status     ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
  closed_at  DATETIME    NULL,
  closed_by  VARCHAR(64) NULL,
  ...
);
```

**Sifar rujukan Java.** Disahkan:

```bash
grep -rn "accounting_period\|AccountingPeriod" src/main/java/ --include="*.java"
# (kosong)
```

Ini niat masa depan: **tutup buku bulanan** (lock period supaya tiada posting ke period yang sudah ditutup). Belum direka, belum dilaksana.

**Jangan** guna semula lajur ini untuk perkara lain. Bila tutup buku dilaksana, ia perlukan lajurnya sendiri.

---

## `financial_document.period_id` — telah dialih maksud

Sejarah lajur ini menjelaskan banyak kekeliruan:

| Ver | Apa berlaku |
|---|---|
| V1 | `period_id BIGINT` dicipta, FK → `accounting_period(id)`. **Tidak pernah diisi.** |
| V6 | `period VARCHAR(7)` ditambah kerana entiti ditulis guna `String`. Komen V6: *"period tertinggal — entiti FinancialDocument perlukannya"*. Dua lajur period wujud; satu mati. |
| V19 | `period` digugurkan. `period_id` dialih FK → `fi_period(period_id)`. Kini membawa period **LARIAN**. |

**Sekarang:**

```
financial_document.period_id       -> fi_period, aras charge_frequency AKAUN   (period LARIAN)
financial_document_line.period_id  -> fi_period, aras charge_frequency PRODUK  (period LIPUTAN)
```

Nota untuk tutup buku nanti: ia perlukan `accounting_period_id` yang **berasingan**. Jangan kongsi `period_id`.

---

## `journal_entry.period_id` — masih menunjuk `accounting_period`

```sql
-- V1__init_schema.sql:514, 531
period_id BIGINT NULL,
CONSTRAINT fk_journal_period FOREIGN KEY (period_id) REFERENCES accounting_period (id)
```

**Belum disemak.** Sama ada ia patut menunjuk `fi_period` (macam `financial_document`) atau kekal untuk tutup buku — belum diputuskan.

Kalau `LedgerService` mengisinya, semak apa yang ia isi. Kalau tidak, ia lajur mati kedua.

```bash
grep -rn "periodId\|period_id" src/main/java/com/monthley/ledger/
```

---

## `product.charge_1st_mon` (production `mon_sp_prod`)

Semua nilai `0` dalam production. Nampak macam **anchor month yang tidak siap** — niat asal partner yang tidak pernah dilaksana.

Skema baru guna `product.anchor_month` untuk tujuan ini. Belum disahkan sama ada `charge_1st_mon` ada niat berbeza.

Soalan terbuka #2 dalam `README.md`.

---

## `product.generation_day` — digugurkan (V17)

Semua `0` dalam production (`mon_sp_prod.gen_day`). Penjadualan berlaku di aras SP (`sp_billing_setting.invoice_gen_day`), bukan produk.

---

## `account_subscription.last_charged_at` — digugurkan (V17)

Penunjuk boleh-ubah, punca drift. Idempotency kini melalui `financial_document_line.idem_key` (UNIQUE, STORED GENERATED).

Rujuk `docs/domain/legacy-generator-analysis.md` §5.2

---

## Cara semak sendiri

Sebelum membina atas mana-mana table:

```bash
# Ada kod Java yang menyentuhnya?
grep -rn "<nama_table>\|<NamaEntiti>" src/main/java/ --include="*.java"

# Ada data?
mysql -u monthley --table -pdevpass monthley_new -e "SELECT COUNT(*) FROM <nama_table>;"
```

Kosong pada kedua-dua = simpanan, bukan reka bentuk aktif.
