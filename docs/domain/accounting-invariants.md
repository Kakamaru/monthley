# Invariant Perakaunan

> Sumber: siasatan CASE-001 (production, 17 Julai 2026)
> Status: guard dipersetujui, pelaksanaan belum

Tiga keluarga bug ditemui dalam production. Dokumen ini menerangkan setiap satu,
guard yang menghalangnya dalam sistem baru, dan kenapa guard yang lebih jelas
tidak mencukupi.

---

## 1. Kenapa reconciliation job tidak memadai

Bug A0124 **tidak akan** tertangkap oleh job yang membandingkan `bal_amt` dengan
baki diterbitkan. Dari sudut `doc_link`, semuanya seimbang cantik
(817.70 = 817.70). Yang salah ialah **kewujudan dokumen yang tidak sepatutnya
wujud**.

Lebih teruk: kes ini dijumpai **secara kebetulan**. Kalau kedua-dua J00 pendua
menolak baki dengan betul:

```
854.30 − 7.10 − 7.10 = 840.10
bal_amt akhir = 35.40
amt_actv      = 35.40
→ TALLY. Tiada gap. Tiada aduan.
```

Tunggakan RM7.10 dari 2019 hilang senyap selama-lamanya, dan **tiada satu pun
reconciliation job boleh mengesannya** — kerana semua nombor bersetuju.

Gap RM7.10 yang membawa kepada penemuan ini wujud kerana **lost update gagal
separuh jalan**. Dua bug saling membatalkan: posting pendua (+7.10 silap) dan
lost update (−7.10 silap). `bal_amt = 42.50` betul secara kemalangan, bukan
secara reka bentuk.

**Implikasi:** idempotency + invariant ialah pertahanan utama.
Reconciliation ialah jaring, bukan penyelesaian.

Soalan terbuka: berapa banyak lagi pendua yang "berjaya" sepenuhnya dan kini
duduk senyap dalam production dengan semua nombor tally?

---

## 2. Family 1 — Posting jurnal pendua

### Kes

Akaun A0124 (SP `000Y`). Dua J00 identik dicipta pada saat sama
(2020-05-20 21:00:11, user `00IK`, remark "ERROR FOR INSURANCE"). Kedua-dua
knock invois yang sama.

Kesan: tunggakan sah RM7.10 (Arrears Carried Forward 2019) terhapus ~6 tahun.

### Kenapa pengesanan-bentuk tidak boleh

Pendua tidak boleh dikesan dari bentuk data. Batch generation yang **sah** pun
menghasilkan bentuk yang sama: akaun sama, jenis sama, amaun sama, saat sama.

Kunci `(akaun, jenis, amaun, masa)` akan menolak batch generation dan bulk
cancel yang sah.

Kalau tidak boleh dikesan dari bentuk, tidak boleh dihalang dari bentuk.

### Guard — token niat

Kunci idempotency atas **niat operasi**, bukan bentuk data:

```
(sp_code, account_id, doc_type, source_ref, period_id)
```

`source_ref` mesti **NOT NULL**:

| Sumber | `source_ref` |
|---|---|
| Batch generation | `invoice_run_item.id` (dijana mesin) |
| Bayaran | `payment.id` |
| **Catatan manual** | **Token UUID dari klien** |

**Token di-mint bila borang dibuka, bukan bila submit.** Kalau di-mint masa
submit, double-click menghasilkan dua token dan guard terlepas.

Ujian kewarasan: user betul-betul mahu dua pelarasan RM7.10 pada hari sama
→ buka borang dua kali → dua token → dua-duanya lulus. Betul.

Submit kedua dengan token sama → dikenali sebagai operasi sama → pulangkan
hasil asal, jangan cipta baru. (Corak Stripe.)

### Nota

Cadangan awal menggunakan `source_ref` nullable. Ia **gagal** untuk kes yang
ia direka untuk halang: jurnal manual tiada operasi hulu, jadi `source_ref`
= NULL, dan NULL berulang dibenarkan dalam unique index. Token klien
menyelesaikan ini.

---

## 3. Family 2 — Over-allocation

### Kes

**Masih hidup.** Kes terbaru 2026-05-07. 387 invois, ~RM19,147, merentasi 21 SP.

Contoh: invois RM7.20 di-allocate RM376.70. Invois RM40 di-knock RM80.

Punca: tiada validation semasa write.

### Guard — invariant + kunci pesimis

```
SUM(active allocations) <= document.amount
```

Dikuatkuasakan **semasa write**, bukan dikesan selepas bocor.

Semak-lepas-baca tanpa kunci mempunyai lubang race:

```
T1: SELECT SUM(...) → 0. Lulus.
T2: SELECT SUM(...) → 0. Lulus.
T1: INSERT 40. T2: INSERT 40.
→ Invois RM40, allocated RM80.
```

Itu tepat corak yang ditemui. Unique constraint tidak boleh digunakan (ia
agregat, bukan kesamaan). Perlu kunci pesimis dengan **dokumen sebagai
sempadan agregat**:

```java
var doc = em.find(FinancialDocument.class, docId, LockModeType.PESSIMISTIC_WRITE);
var allocated = allocationRepo.sumActiveByDocument(docId);
if (allocated.add(amount).compareTo(doc.getAmount()) > 0) {
    throw new OverAllocationException(docId, doc.getAmount(), allocated, amount);
}
```

Semua allocation ke dokumen tersebut bersiri.

### Tiga laluan allocation

Invariant mesti dikuatkuasakan pada **semua**:

1. Bayaran
2. Knock manual
3. **Auto-knock kredit semasa penjanaan invois** — lihat `legacy-generator-analysis.md` §3.7

Laluan ketiga mudah terlepas pandang. Dalam legacy, `receipts` tidak dikunci.

### Invariant vs migrasi

387 kes tersebut ialah **soalan migrasi**, bukan soalan reka bentuk.

Walaupun sebahagiannya "sah" mengikut semantik longgar sistem lama, dalam
sistem baru ia akan dimodelkan berbeza — sebagai kredit/pendahuluan. Jadi
`<=` betul sebagai peraturan, tidak kira apa jawapan untuk data lama.

Data lama = kerja cleanup. Berasingan.

### Hipotesis belum diuji

`amt_` invois diubah **selepas** allocation dibuat. Invois asal RM350,
di-allocate penuh, kemudian `amt_` di-edit jadi RM300 → over-allocation
tanpa sebarang double-knock.

Kalau betul, ini bug family keempat — **dokumen mutable**.

Guard: `document.amount` **immutable selepas diposkan**. Nak ubah? Contra +
terbit semula. Kalau amaun boleh berubah selepas allocation, setiap invariant
yang bergantung padanya jadi sementara.

Corak B dalam CASE-001 kekal **BLOCKED** sehingga hipotesis ini diuji.
Over RM50 tetap merentasi invois RM290 dan RM300 — tidak berkadar (jadi bukan
cukai), terlalu konsisten untuk jadi kemalangan.

---

## 4. Family 3 — Posting asimetri

### Kes

J00 hantu mencipta doc + link + txn, **dan** menggerakkan akaun kawalan,
**tetapi tidak** menggerakkan baki pelanggan. Double-entry pincang separuh.

| | Kawalan `000Y00001` | Pelanggan `000Y00025` |
|---|---|---|
| J00 #1 | −954,060.95 | 847.20 ✓ |
| J00 #2 | −954,053.85 ✓ bergerak | 847.20 ✗ tak bergerak |

### Mekanisme

Skema lama menyimpan **satu baris = dua kaki**:

```
MY3GCA  dt_acc_id 000Y00001  dt_acc_amt -954053.85
        cr_acc_id 000Y00025  cr_acc_amt    847.20
        amt_ 7.1000
```

`amt_ = 7.10` untuk kedua-dua belah. **Entry itu seimbang.** Invariant
balanced-entry tidak akan menangkap bug ini.

Yang menyimpang bukan amaun — tetapi **snapshot baki berjalan**.

Hipotesis: kawalan menggunakan `UPDATE ... SET bal = bal - amt` (atomik,
bersiri). Pelanggan menggunakan baca-kira-tulis (`SET bal = <nilai dikira>`).
Dua insert serentak → kedua-dua baca 854.30 → kedua-dua tulis 847.20.
**Lost update.**

Ini race yang **sama** seperti family 2. Bukan dua isu berasingan.

### Guard — baki diterbitkan

Kalau baki diterbitkan, **tiada "baki" untuk digerakkan**. Asimetri
"gerak kawalan tapi tak gerak pelanggan" menjadi mustahil — hanya ada satu
tempat.

Family 3 **larut sepenuhnya**. Tiada mekanisme baru diperlukan.

### Nota: balanced-entry tetap berbaloi

Untuk skema baru (`journal_entry` + `journal_line` berbilang), entry tidak
seimbang **memang mungkin**:

```
SUM(debit lines) == SUM(credit lines)   // setiap journal_entry
```

Kuatkuasakan di sempadan agregat `JournalEntry`. Tetapi ini guard berasingan
untuk masalah berasingan — bukan penawar untuk family 3.

---

## 5. Bukti dari `InvoiceGenerator`

Laluan invois **mengunci kedua-dua akaun**:

```java
// generateForSpAccounts — sekali per batch 50
session.lock(spAcc, LockMode.PESSIMISTIC_WRITE);
// generateInvoiceForAccount — per akaun
session.lock(acc, LockMode.PESSIMISTIC_WRITE);
```

Kemas kini baki menggunakan baca-kira-tulis:

```java
accBal = accBal.add(totalAmt);
acc.setBalanceAmount(accBal);       // SET ke nilai dikira
spAcc.setBalanceAmount(spAccBal);   // sama
```

Selamat **hanya kerana** kunci itu ada.

**J00 hantu datang dari laluan lain yang tidak mengunci.**

Ini hujah terkuat untuk pendekatan invariant: **disiplin kunci per-laluan
gagal**. Satu laluan ingat, satu lupa. Invariant mesti duduk dalam domain,
bukan bergantung pada setiap caller.

Komen pengarang legacy sendiri: `// this is heavy, that's why some functions
must be stopped` — kunci aras SP membekukan seluruh SP semasa larian.

---

## 6. Pengesahan reka bentuk sedia ada

"Ledger sumber kebenaran, baki diterbitkan" — **betul**, dan penemuan ini
menguatkan lagi.

Kalau baki diterbitkan, family 1 & 3 hilang sepenuhnya — tiada dua tempat
untuk menjadi tidak selaras.

---

## 7. Senarai tindakan

- [ ] `source_ref NOT NULL` pada dokumen; token UUID klien untuk catatan manual
- [ ] Token di-mint bila borang dibuka (frontend)
- [ ] Kunci pesimis + invariant `SUM(allocations) <= document.amount`
- [ ] Invariant pada **tiga** laluan allocation (bayaran, manual, auto-knock)
- [ ] `document.amount` immutable selepas diposkan
- [ ] Balanced-entry invariant pada agregat `JournalEntry`
- [ ] Posting ledger **segerak**, transaction sama dengan penciptaan dokumen — jangan jadikan event Modulith (`@ApplicationModuleListener` ialah `@Async` + `REQUIRES_NEW`, akan mencipta semula family 3 sebagai seni bina)
- [ ] Query forensik: pendua J00 manual merentasi 71 SP

### Query forensik (belum dijalankan)

```sql
SELECT sp_code, mbr_acc_id, doc_type, create_by, create_dt, amt_,
       COUNT(*) AS bil, GROUP_CONCAT(doc_id) AS docs
FROM mon_sp_fi_doc
WHERE doc_type IN ('J00','J01','J02','JNL')
  AND sts_code = 'A'
GROUP BY sp_code, mbr_acc_id, doc_type, create_by, create_dt, amt_
HAVING COUNT(*) > 1
ORDER BY bil DESC, amt_ DESC;
```

Hujah "tidak boleh dikesan dari bentuk" benar untuk **INV** — batch generation
yang sah memang berbentuk sama. Tetapi J00 **manual**, oleh user manusia,
dengan remark, pada saat yang sama? Batch generation bukan pengganggu di sini.

Dua soalan berbeza:
- **Cegah dalam sistem baru** → token niat
- **Forensik atas data lama** → pengesanan bentuk (boleh, untuk jurnal manual)

Setiap hit = satu tunggakan yang hilang senyap. A0124 = RM7.10. Merentasi
71 SP dan 6 tahun, jumlahnya mungkin bukan kecil.
