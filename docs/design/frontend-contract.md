# Kontrak Frontend ⇄ Backend

> Kemas kini: 18 Julai 2026
> Sumber: cross-check `Monthley Portal.dc.html` (prototaip UI penuh) +
> `Monthley Handoff.html` lawan enjin bil yang dibina.

## Status dokumen sumber

- **`Monthley Portal.dc.html`** — prototaip UI penuh dengan Angular templating.
  Menunjukkan medan SEBENAR setiap skrin. LEBIH BAHARU dari handoff — ada 6
  frekuensi, prorated, exclude period. Rujukan utama untuk bentuk UI.
- **`Monthley Handoff.html`** — struktur Angular + endpoint. Lapuk pada enum
  (4 frekuensi). Guna untuk endpoint, bukan model.

---

## Skrin ⇄ Enjin — PADAN

Skrin Invois Setting mengesahkan model enjin sepenuhnya:

| UI field (Invois Setting) | Enjin / skema |
|---|---|
| Mode: Postpaid/Current/Prepaid | GenMode |
| Frequency: Monthly/Quarterly/Half Year/Year | account.charge_frequency (ufuk) |
| Split Invoice Transaction | invoice_grouping |
| Payment Term (days) | sp_billing_setting.payment_term_days |
| Manage Exclude Period (Period+Remarks) | invoice_exclude_period.period_id |
| Tax Name/Rate/Sales Tax ID | sp_billing_setting.tax_rate |

Add Product: Main/Mandatory/Prorated Yes/No, 6 frekuensi, Rate, Category — semua
padan ProductView. Mode dan Frequency BERASINGAN — sahkan model dua-paksi.

---

## Percanggahan DISELESAIKAN 18 Julai

1. **Non-Recurring = One Time.** Buang Non-Recurring, kekal One Time -> ONE_TIME.
2. **anchor_month ditambah dalam Add Product** — MUNCUL bila frequency in
   {Quarterly, Half Yearly, Yearly}, sembunyi untuk lain. Tanpa anchor,
   kitaran QR/HF/YR default Januari.
3. **chargeFrequency ejaan:** ONE_TIME/MONTHLY/QUARTERLY/HALF_YEAR/YEAR/PER_USE.
4. **startDate DATE**, bukan "2026-07". Suis proration perlu hari.

---

## Jurang — UI ada, skema/enjin BELUM

| UI | Perlu | Bila |
|---|---|---|
| Invoice Generation Day Of Month | V22: invoice_gen_day | (b) |
| Invoice No. Prefix/Size/Starts From | V22: format nombor | (b) |
| allow_price_override | V22 | (b) |
| smallest_denomination | V22 | (b) |
| Late Penalty | Skema penalti | Fasa 2 |
| e-Invois | Skema e-invois | Jan 2027 |
| Pelan/Lesen | Modul pakej | Fasa 2 |
| ADJ_ADD/REDUCE | Pemetaan jurnal + invariant | Belum |

---

## Modul UI, BELUM dibina

Adhoc Invois, Usage upload, Perbelanjaan, Aduan, Memo, Derma, Portal Pelanggan,
FPX callback, Kad Akses/IoT. Aduan/Memo/Derma dikawal PELAN (fasa 2).

---

## Adjustment = allocation manual (PENTING)

Skrin Account Adjustment (ADD/REDUCE) menyasar invois tertentu — laluan ketiga
family 2 (CASE-001). Invariant SUM(allocations) <= document.amount MESTI
dikuatkuasa dengan kunci pesimis. Rujuk accounting-invariants.md §3.
BELUM: ADJ_ADD/REDUCE -> J00 atau JNL?

---

## Interface TypeScript untuk diselaraskan

chargeFrequency: 'ONE_TIME'|'MONTHLY'|'QUARTERLY'|'HALF_YEAR'|'YEAR'|'PER_USE'

Product: + anchorMonth (1-12, WAJIB bila QR/HF/YR), incomeGlAccountId
ProductSubscription: startDate ISO "2026-06-15" (bukan "2026-07"), endDate?,
unitPrice? (hormati allow_price_override)
