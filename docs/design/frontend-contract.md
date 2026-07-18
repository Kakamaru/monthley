# Kontrak Frontend ⇄ Backend

> Kemas kini: 18 Julai 2026
> Sumber: cross-check `Monthley Handoff.html` lawan enjin bil yang dibina.

## Status handoff

`Monthley Handoff.html` ditulis SEBELUM interogasi production. Ia snapshot
pemahaman awal, BUKAN kebenaran yang backend mesti ikut. Di mana ia bercanggah
dengan enjin, enjin betul — ia dibina atas data prod sebenar.

---

## Percanggahan — enjin menang

### 1. chargeFrequency: 6 nilai, bukan 4
Handoff: MONTHLY | YEARLY | ONE_OFF | PER_USED
Enjin:   ONE_TIME | MONTHLY | QUARTERLY | HALF_YEAR | YEAR | PER_USE

Handoff tiada QUARTERLY dan HALF_YEAR. Production ada 361 invois QR + ribuan HF.
Frontend hantar 6 nilai enjin, ejaan tepat: YEAR bukan YEARLY, ONE_TIME bukan
ONE_OFF, PER_USE bukan PER_USED.

### 2. startPeriod -> start_date (DATE)
Handoff simpan "2026-07" (bulan). Enjin guna start_date DATE sebagai suis
proration. Bulan sahaja -> proration tarikh mustahil. Frontend hantar tarikh
penuh. Rujuk billing-rules.md §6.

### 3. DocumentType — perlu pemetaan
Handoff: INVOICE | RECEIPT | ADJ_ADD | ADJ_REDUCE
Enjin:   INV | RCP | J00 | J01 | J02 | JNL

BELUM DIPUTUSKAN: ADJ_ADD/REDUCE petakan ke J00 atau JNL?

### 4. Adjustment = allocation manual
Adjustment menyasarkan invois tertentu — laluan ketiga family 2 (CASE-001).
Invariant SUM(allocations) <= document.amount MESTI dikuatkuasa di sini.
Rujuk accounting-invariants.md §3.

---

## Padan — tiada perubahan
- POST /tools/generate-invoices betul
- Peranan SP_ADMIN + RoleGuard (V12/V13)
- Manual payment, batal dokumen, SP statement
- GenerateRequest.periods[] sepadan konsep ufuk

---

## Modul handoff, BELUM dibina
Adhoc invois, Usage upload (PER_USE), Expenses, Complaints, Memo, Donation,
Portal Pelanggan, FPX callback. Fasa akan datang.

---

## Interface TypeScript yang perlu diselaraskan bila bina frontend

chargeFrequency: 'ONE_TIME'|'MONTHLY'|'QUARTERLY'|'HALF_YEAR'|'YEAR'|'PER_USE'

ProductSubscription: startDate ISO "2026-06-15" (bukan "2026-07"), endDate?,
unitPrice? (hormati allow_price_override).

Product: tambah anchorMonth? (1-12), prorated, incomeGlAccountId?.
