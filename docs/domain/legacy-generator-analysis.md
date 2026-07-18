# Analisis `InvoiceGenerator` Legacy

> Sumber: `com.monthley.account.InvoiceGenerator` (1,958 baris, pengarang `hishammk`)
> Dibaca: 17 Julai 2026
> Tujuan: pastikan enjin baru tidak terlepas apa-apa, dan tidak mewarisi bug.

Dokumen ini memetakan enjin lama kepada reka bentuk baru. Tiga bahagian:
**disahkan** (kita betul), **jurang** (kita terlepas), **bug** (jangan tiru).

Untuk peraturan domain yang muktamad, lihat `billing-rules.md`. Dokumen ini
ialah bukti dan asal-usul.

---

## 1. Aliran penuh

### `generateForSpAccounts(spCode, toCharge, preventDuplicate)`

```
1. Cari SP + akaun kawalan SP
2. Kira refNoFormat + refNoSerial   ← berasaskan COUNT, lihat §4
3. Gelung halaman 50 akaun
   ├── Transaction per batch
   ├── session.lock(spAcc, PESSIMISTIC_WRITE)      ← kunci seluruh SP
   └── Untuk setiap akaun:
       ├── nextChargePeriod = getSpNextChargePeriod(sp, acc)
       ├── Semak exclude → continue (langkau AKAUN)
       └── split ? generateInvoiceMultipleForAccount : generateInvoiceForAccount
4. commit batch
```

Nota: `toCharge` bertanda *"USE FOR DEMO ONLY AND WILL BE REMOVED"*.

### `getSpNextChargePeriod(sp, acc)`

```java
if (genMode.equals("F"))      p = periodServ.getFuturePeriod(acc.getChargeCode().getCode());
else if (genMode.equals("P")) p = periodServ.getPreviousPeriod(acc.getChargeCode().getCode());
else if (genMode.equals("C")) p = periodServ.getCurrentPeriod(acc.getChargeCode().getCode());
else throw new UnsupportedOperationException("Unsupported mode");
```

Mod: `F` = prepaid (future), `P` = postpaid (previous), `C` = current.

**Anjakan berlaku pada aras `charge_code` AKAUN.** Akaun tahunan dalam mod postpaid
→ period = tahun **lepas**, bukan bulan lepas.

### `generateInvoiceForAccount(...)`

```
├── Langkau kalau acc.startDate selepas period.endDate
├── Langkau kalau acc.expiryDate sebelum period.startDate
├── Langkau kalau tiada langganan
├── session.lock(acc, PESSIMISTIC_WRITE)
│
├── Untuk setiap langganan:
│   ├── Langkau kalau parentSubscriptionId tidak kosong    ← anak diabaikan
│   ├── Langkau kalau produk PU (per-use, dikendali kemudian)
│   ├── Kalau 1T: langkau kalau lastChargedPeriod != null  ← sekali seumur hidup
│   ├── Kalau prodChargeCode != acc.chargeCode:
│   │      periods = getIntertwinedPeriods(chargePeriod, prodChargeCode)
│   ├── Tapis period: sub.start selepas period.end → buang
│   │                 sub.end sebelum period.start → buang
│   │                 lastChargedPeriod >= period  → buang
│   └── Untuk setiap period yang tinggal:
│       ├── Langkau kalau qty <= 0
│       ├── Kalau bukan 1T dan isProrated == 'Y': rate = calculateProrated(...)
│       ├── priceUnit = sp.allowPriceOverride=='Y' && sub.priceUnitOverride!=null
│       │              ? sub.priceUnitOverride : prod.priceUnit
│       ├── amtCharged = priceUnit × qty × rate           ← BUG: rate², lihat §4
│       ├── taxAmount  = amtCharged × sp.salesTaxRate
│       ├── fullAmount = roundCurrency(smallestDenom, amtCharged + taxAmount)
│       ├── txn.period = nextProductPeriod (1T → tahun semasa)
│       ├── txn.debit = acc, txn.credit = sp.accountId
│       ├── Snapshot baki berjalan pada txn                ← punca drift
│       └── sub.lastChargedPeriod = nextProductPeriod
│
├── Per-use: usages PENDING dari mon_acc_subscr_usg → createTransactionFromUsage
├── Kalau tiada txn → return null
├── receipts = docServ.findWithActiveAmount(sp, acc, "RCP", -1)
├── createInvoice(...) → auto-knock kredit, cipta FiDocumentLink
├── Simpan txn (id dari FI_TXN_SEQ)
├── Tanda usages sebagai DONE
├── Simpan links
├── acc.balance += totalAmt; acc.lastChargedPeriod = chargePeriod
├── spAcc.balance -= totalAmt
└── Baris giliran notifikasi
```

### `createInvoice(...)`

```java
inv.setDocumentId(seqServ.getSequence("FI_DOC_SEQ"));
inv.setUuid(UUID.randomUUID().toString());
inv.setReferenceNo(String.format(refNoFormat, refNoSerial));
inv.setDueDate(now.toLocalDate().plusDays(sp.getTermDays()));   // dari tarikh JANA
inv.setFiPeriod(chargePeriod.getPeriodId());
inv.setActivateDate(chargePeriod.getStartDate());
```

Kalau `accBal < 0` (pelanggan ada kredit) → knock resit sedia ada, cipta
`FiDocumentLink` untuk setiap knock, kurangkan `r.amountActive`.

---

## 2. Disahkan — reka bentuk kita betul

| Perkara | Bukti legacy |
|---|---|
| Mod anjak period asas | `getFuturePeriod` / `getPreviousPeriod` / `getCurrentPeriod` |
| `doc.period_id` aras = `account.charge_freq` | `getSpNextChargePeriod` hantar `acc.getChargeCode()` |
| `line.period_id` aras = `product.charge_freq` | `txn.setPeriod(nextProductPeriod.getPeriodId())` |
| Ufuk × aras melalui pengembangan | `getIntertwinedPeriods(chargePeriod, prodChargeCode)` |
| Tarikh berkesan = MAX/MIN dua aras | `calculateProrated` — lihat di bawah |
| Price override per langganan | `sub.getPriceUnitOverride()` |
| Prorate ialah bendera per produk | `prod.getIsProrated() == 'Y'` |
| Snapshot baki ialah punca drift | `txn.setDebitAccountBalance(accBal.add(totalAmt))` |

### `calculateProrated` — rantaian tarikh

```java
LocalDate effectiveStart = nextProductPeriod.getStartDate();
if (acc.getStartDate() != null && acc.getStartDate().isBefore(period.getEndDate())
        && acc.getStartDate().isAfter(period.getStartDate()))
    effectiveStart = acc.getStartDate();
if (sub.getStartDate() != null && sub.getStartDate().isAfter(effectiveStart)
        && sub.getStartDate().isBefore(period.getEndDate()))
    effectiveStart = sub.getStartDate();
// ... sama untuk effectiveEnd dengan MIN
```

Padan rantaian kita: `MAX(period.start, acc.start, sub.start)` /
`MIN(period.end, acc.end, sub.end)`.

Penyebut: `DAYS.between(period.start, period.end) + 1` — **hari sebenar period**,
bukan 30 tetap. Mengesahkan keputusan kita.

---

## 3. Jurang — yang kita terlepas

### 3.1 `sp.allow_price_override` — gate aras SP

```java
if (sp.getAllowPriceOverride() == 'Y'){
    if (sub.getPriceUnitOverride() != null) priceUnit = sub.getPriceUnitOverride();
}
```

Kalau SP tidak benarkan, `account_subscription.unit_price` **diabaikan sepenuhnya**.
Kita tiada setting ini.

**Tindakan:** tambah `sp_billing_setting.allow_price_override BOOLEAN`.

### 3.2 Pembundaran denominasi terkecil

```java
BigDecimal currencySmallestDenominator =
    sp.getEffectiveCurrencySmallestDenomination() == null ||
    sp.getEffectiveCurrencySmallestDenomination().equals(BigDecimal.ZERO)
        ? sp.getCurrency().getSmallestDenomination()
        : sp.getEffectiveCurrencySmallestDenomination();

fullAmount = currencyServ.roundCurrency(currencySmallestDenominator.doubleValue(),
                                        amtCharged.add(taxAmount));
```

Bundarkan amaun akhir ke denominasi terkecil (cth 5 sen). Ada override per SP.
Menjejaskan **setiap** invois, bukan kes tepi. Kita tiada langsung.

**Tindakan:** tambah `currency.smallest_denomination` +
`sp_billing_setting.effective_smallest_denomination`, dan fungsi pembundaran.

### 3.3 Pakej — anak langganan diabaikan

```java
// for calculating invoice, children subs rates are ignored. Only their parent are included
// those item (not chargable) are not itemized in invoice
if (!StringUtil.isBlank(sub.getParentSubscriptionId())) continue;
```

Hanya parent dicaj. Anak **tidak** muncul sebagai baris invois langsung.
Ini menjawab soalan terbuka #2 dalam `billing-rules.md`.

**Tindakan:** enjin mesti tapis `parent_subscription_id IS NULL`.

### 3.4 `ONE_TIME` (`1T`)

```java
if ("1T".equals(prodChargeCode.getCode())) {
    if (sub.getLastChargedPeriod() != null) continue;   // caj sekali seumur hidup
}
...
if (!"1T".equals(prodChargeCode.getCode())) {
    txn.setPeriod(nextProductPeriod.getPeriodId());
} else {
    txn.setPeriod(periodServ.getCurrentYearPeriod().getPeriodId());   // period = TAHUN semasa
}
```

Juga: `1T` **tidak pernah** diprorate (semakan `!"1T".equals(...)` pada gate proration).

**Tindakan:** `ChargeFrequency.ONE_TIME` perlu peraturan — caj sekali,
`period_id` = tahun semasa, tiada proration. Idempotency melalui `idem_key`
memerlukan `period_start` yang stabil (guna 1 Jan tahun semasa).

### 3.5 `PER_USE` (`PU`) — laluan berasingan

```java
if ("PU".equals(prod.getChargeCode().getCode())) continue;   // dilangkau gelung utama
...
List<AccountSubscriberUsage> usages = usageService.getPendingForAccount(spCode, accId);
```

Baris `mon_acc_subscr_usg` berstatus PENDING disapu masuk invois seterusnya,
kemudian ditanda `'D'` (done) dengan `invoiceId`.

`createTransactionFromUsage`:
- `amtCharged = u.getAmount()` atau `prod.priceUnit × u.quantity`
- **Tiada price override** (guna `prod.getPriceUnit()` terus)
- **Tiada proration**
- `txn.setPeriod(u.getPeriod())` — **boleh NULL**
- Deskripsi dari `u.getRemarks()` (dipotong 100 aksara)

**Tindakan:** modul usage + status queue. `line.period_id` mesti kekal nullable.

### 3.6 Penalti lewat mengalir melalui usage

```java
if (u.getTransactionCode().equalsIgnoreCase("M1500")) {
    txn.setPriceUnit(u.getAmount());
} else {
    txn.setPriceUnit(prod.getPriceUnit());
}
```

Penalti lewat **bukan modul berasingan** — ia baris usage dengan `txn_code = M1500`.

Taksonomi `txn_code` yang diketahui:

| Kod | Makna |
|---|---|
| `M1000` | Caj berkala (`TXN_CODE_PERIODIC_CHARGE`) |
| `M1500` | Penalti lewat |

**Tindakan:** sahkan senarai penuh `txn_code` dari prod. Reka `transaction_code`
sebagai rujukan, bukan enum keras.

### 3.7 Auto-knock kredit semasa penjanaan invois

```java
List<ServiceProviderFiDocument> receipts =
        docServ.findWithActiveAmount(sp.getSpCode(), acc.getAccountId(), "RCP", -1);
inv = createInvoice(..., receipts, accBal, links);
```

Dalam `createInvoice`, kalau `accBal < 0`:

```java
for (ServiceProviderFiDocument r : receipts) {
    if (r.getAmountActive().compareTo(bal) > 0) {
        r.setAmountActive(r.getAmountActive().subtract(bal));
        links.add(new FiDocumentLink(seq, r.getDocumentId(), inv.getDocumentId(), bal));
        bal = ZERO; break;
    } else {
        BigDecimal usage = r.getAmountActive();
        bal = bal.subtract(r.getAmountActive());
        r.setAmountActive(ZERO);
        links.add(new FiDocumentLink(seq, r.getDocumentId(), inv.getDocumentId(), usage));
    }
}
```

**Penjanaan invois ialah operasi allocation.** Contoh Kama: pelanggan bayar
RM2,000 awal tahun → advance. Invois RM200 dijana → baki jadi −RM1,800.
Setiap invois berikutnya knock baki advance sehingga habis.

Ini laluan ketiga yang boleh hasilkan over-allocation (family 2), selain
bayaran dan knock manual. Dan `receipts` **tidak dikunci**.

**Tindakan:** invariant allocation mesti dikuatkuasakan di sini juga, bukan
hanya pada laluan bayaran.

### 3.8 Cukai di aras SP

```java
BigDecimal taxRate = sp.getSalesTaxRate() == null ? BigDecimal.ZERO : sp.getSalesTaxRate();
```

Satu kadar untuk seluruh SP. Bukan per produk.

### 3.9 Due date dari `sp.term_days`

```java
LocalDate due = now.toLocalDate().plusDays(sp.getTermDays());
// komen pengarang: "set due date according to date of generation (might not be a good solution)"
```

Dari **tarikh jana**, bukan tarikh dokumen. Pengarang sendiri ragu.

Mengesahkan `term_days` = tempoh bayaran, **bukan** penyebut proration.

### 3.10 Semakan lain

- `acc.getExpiryDate()` — akaun tamat tempoh dilangkau
- `qty <= 0` → langkau
- Tiada langganan → langkau akaun
- `acc.setLastChargedPeriod(chargePeriod)` — akaun ada pointer **juga**, bukan hanya langganan

---

## 4. Bug — jangan tiru

### 4.1 `rate` didarab dua kali

```java
rate = calculateProrated(acc, sub, prod, nextProductPeriod, totalEffectiveDays);
qty = qty.multiply(rate).setScale(2, RoundingMode.HALF_UP);       // rate masuk qty
...
BigDecimal amtCharged = priceUnit.multiply(qty).multiply(rate)    // rate DARAB LAGI
                                 .setScale(2, RoundingMode.HALF_UP);
```

`amaun = harga × qty × rate²`

Proration 50% → caj **25%**. Setiap invois prorated dalam production
**terkurang caj**. Hanya 12 daripada 1,478 produk terjejas, tetapi ia nyata.

### 4.2 Presisi `double`

```java
return new BigDecimal((double) d1/totalEfectiveDays);
```

Pembahagian `double` kemudian constructor `BigDecimal(double)` → pengembangan
binari penuh (`0.4666666666666666296592325124947...`).

Betul: `BigDecimal.valueOf(d1).divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_UP)`

### 4.3 Jejak audit pecah

```java
txn.setQuantity(sub.getQuantity());   // qty ASAL, bukan yang diprorate
```

Baris menyimpan `qty = 1` tetapi amaun diprorate. **`amaun` tidak boleh dikira
semula dari `qty × harga`.** Audit mustahil.

### 4.4 Nombor rujukan ada race

```java
long invCurrCount = docQuery.getDocumentCount(spCode, "INV");
long refNoSerial = (sp.getInvoiceNoStart() == null ? 0 : sp.getInvoiceNoStart()) + invCurrCount;
while (docQuery.isDocumentExists(spCode, "INV", String.format(refNoFormat, refNoSerial))) {
    ++refNoSerial;
}
```

Berasaskan `COUNT(*)`, kemudian check-then-act. Dua larian serentak → siri sama.
Dokumen dipadam → kiraan tersasar.

Kita ada `document_number_sequence` — pastikan ia guna `SELECT ... FOR UPDATE`
atau auto-increment, bukan kiraan.

### 4.5 `break` pada exception

```java
} catch (Exception e) {
    ...
    if (t != null) t.rollback();
    break;    // ← seluruh larian berhenti
}
```

Satu akaun rosak → larian berhenti. Batch terdahulu **sudah commit**.
Hasilnya larian separuh: sesetengah pelanggan dibil, sesetengah tidak.

Reka bentuk baru: kumpul ralat per akaun, teruskan, laporkan pada akhir.

### 4.6 Kod mati dalam `createInvoice`

```java
if (accBal.negate().compareTo(totalAmt) > 0) {
    inv.setAmountActive(BigDecimal.ZERO);
} else {
    inv.setAmountActive(totalAmt.add(accBal));
}
// ... gelung knock ...
inv.setAmountActive(bal);   // ← menimpa dua-dua di atas
```

### 4.7 Lain-lain

- Format string rosak: `"Effective Start (%) is later than Effective End (%)"` — `%` bukan `%s`. Akan `throw` bila dicetuskan.
- `taxAmount` tiada `setScale` sebelum ditambah.
- Komen pengarang: `// WARNING: won't generate back dated` — had yang diketahui.
- `calculateProrated` tidak menyemak `isProrated` sendiri; bergantung pada caller. Rapuh.

---

## 5. Perbezaan sengaja dari legacy

### 5.1 Exclude period — prorate, bukan langkau

**Legacy:**

```java
List<InvoicePeriodExclude> periodsToExclude = periodExclService
        .findExcludePeriodForSpByPeriodId(spCode, nextChargePeriod.getPeriodId());
if (!periodsToExclude.isEmpty()) continue;   // langkau AKAUN
```

Padanan `period_id` **tepat pada aras akaun**. Kesannya:

| Akaun | Julai dikecualikan | Legacy |
|---|---|---|
| MONTHLY | `2026230700` padan | Tiada invois ✓ |
| QUARTERLY | `2026230000` (Q3) tidak padan | **Invois penuh, termasuk Julai** ✗ |
| YEARLY | `2026000000` tidak padan | **Invois penuh** ✗ |

Dropdown bulanan-sahaja hanya berkuasa atas akaun bulanan. Akaun QR/YR tidak
pernah terkesan.

**Baru:** exclude prorate ikut **bulan** yang dikecualikan.

| Akaun | Produk | Tanpa exclude | Julai dikecualikan |
|---|---|---|---|
| MONTHLY | MO RM80 | 1 baris Julai | Tiada invois |
| QUARTERLY | MO RM80 | Jul, Ogos, Sep | 2 baris = RM160 |
| QUARTERLY | QR RM240 | Q3 RM240 | 240 ÷ 3 × 2 = **RM160** |
| YEARLY | YR RM350 | 2026 RM350 | 350 ÷ 12 × 11 = **RM320.83** |

Beberapa bulan dikecualikan → prorate mengikut kiraan.

**Dua mekanisme proration berbeza:**

| Pencetus | Penyebut |
|---|---|
| Tarikh mula/tamat | **Hari sebenar** dalam period |
| Exclude period | **Bilangan bulan** dalam period |

**Belum diputuskan:** adakah proration exclude terpakai walaupun
`product.prorated = 0`? Exclude ialah keputusan SP (bulan cuti), manakala
bendera `prorated` tentang kemasukan tengah-kitaran. Kebimbangan berbeza —
cadangan: exclude sentiasa prorate, tidak kira bendera. Perlu pengesahan.

### 5.2 Pointer dibuang

Legacy ada **dua** pointer: `acc.lastChargedPeriod` dan `sub.lastChargedPeriod`.
Kedua-dua digugurkan (V17). Idempotency melalui `idem_key` UNIQUE.

Kesan sampingan: had `// WARNING: won't generate back dated` hilang. Penjanaan
belakang-tarikh menjadi mungkin — yang membolehkan ciri "user tick period mana
nak dicaj".

### 5.3 Baki diterbitkan

Legacy simpan snapshot baki berjalan pada setiap txn (`dt_acc_amt`,
`cr_acc_amt`) **dan** pada akaun (`bal_amt`). Kita tidak simpan mana-mana.
`SUM()` atas ledger.

---

## 6. Belum digali

| Perkara | Kenapa penting |
|---|---|
| `FiPeriodService.getIntertwinedPeriods(period, chargeCode)` | **Teras enjin** — bagaimana period akaun dikembangkan jadi period produk. Fail berasingan, belum dibaca. |
| `FiPeriodService.getFuturePeriod/getPreviousPeriod/getCurrentPeriod` | Bagaimana anjakan mod dilaksana pada setiap aras |
| `generateInvoiceMultipleForAccount` | Laluan split — dibaca separa |
| `generateForProduct` | Penjanaan sasaran-produk |
| `generateForSingleAccount` | Penjanaan akaun tunggal |
| Laluan notifikasi / e-mel penyata | Luar skop enjin bil |
| Senarai penuh `txn_code` | Hanya M1000, M1500 diketahui |

---

## 7. Senarai tindakan

### Tambah pada skema

- [ ] `sp_billing_setting.allow_price_override BOOLEAN`
- [ ] `currency.smallest_denomination DECIMAL`
- [ ] `sp_billing_setting.effective_smallest_denomination DECIMAL NULL`
- [ ] `transaction_code` sebagai jadual rujukan (M1000, M1500, …)
- [ ] Jadual usage (`subscription_usage` — sudah wujud, sahkan bentuk)

### Tambah pada enjin

- [ ] Tapis `parent_subscription_id IS NULL`
- [ ] Peraturan `ONE_TIME`: caj sekali, period = tahun semasa, tiada proration
- [ ] Laluan `PER_USE`: sapu usage PENDING, tanda DONE dengan `invoice_id`
- [ ] Auto-knock kredit semasa penjanaan invois + invariant allocation
- [ ] Pembundaran denominasi terkecil pada amaun akhir
- [ ] Gate price override di aras SP
- [ ] Proration exclude ikut bulan
- [ ] Kumpul ralat per akaun; jangan `break`

### Jangan tiru

- [ ] `rate²` — darab sekali sahaja
- [ ] Pembahagian `double` — guna `BigDecimal.divide` dengan skala
- [ ] Simpan qty asal dengan amaun prorated — simpan qty berkesan
- [ ] Nombor rujukan ikut kiraan — guna jujukan
- [ ] `break` pada exception
- [ ] Snapshot baki berjalan
