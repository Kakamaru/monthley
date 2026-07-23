# CASE-003 — Integrasi Payment Online: Pengajaran dari Legacy

**Untuk:** Team Monthley Baru — modul Payment Online
**Sumber:** Siasatan production legacy (`p302_my` + `mpay`), Julai 2026
**Status:** Punca dianalisis dari data. Pengesahan kod belum dibuat.

---

## 1. Pengenalan

Monthley legacy menerima bayaran online melalui FPX (gateway `mpay`). Aliran ringkasnya:

```
Pelanggan pilih invois
    ↓
pay_bill dicipta (amaun dalam SEN)
    ↓
FPX / bank
    ↓
mpay_ol_pymt_txn (status S = berjaya)
    ↓
Callback → https://monthley.com/callback/mpay
    ↓
Resit (RCP) dicipta dalam mon_sp_fi_doc
    ↓
Link ke invois (mon_fi_doc_link)
```

Dari **20,885 resit online** sejak Januari 2026, majoriti besar betul. Tetapi terdapat **33 anomali** merentasi ~18 bulan di mana **amaun resit tidak sepadan dengan amaun yang sebenarnya dibayar**.

Anomali ini jarang (~1 dalam 200 hingga 1 dalam 600), tetapi kesannya besar: satu kes mencatat **RM310,000** untuk bayaran **RM310**.

**Kenapa ini penting untuk sistem baru:** bug ini tidak menyerlah. Ia tidak menyebabkan error, tidak menghalang transaksi, dan tidak dilaporkan pelanggan. Ia hanya wujud sebagai nombor salah dalam buku SP — dan satu kes terlepas pandang selama **5 bulan** sebelum ditemui secara kebetulan.

---

## 2. Baseline: Apa yang NORMAL

Sebelum melihat anomali, penting faham corak sah. Beza antara `receipt.amt_` dan `pay_bill.amt/100`:

| Beza | Maksud | Kekerapan |
|---|---|---|
| `0.00` | Tiada yuran transaksi | Biasa |
| `−1.50` | Yuran transaksi (bayaran satu invois) | Sangat biasa |
| `−2.00` | Yuran transaksi (bayaran pelbagai invois) | Biasa |
| **Apa-apa lain** | **ANOMALI** | 33 kes / 18 bulan |

> **Nota untuk sistem baru:** yuran ini *disengajakan* — pelanggan bayar RM133.50, SP terima RM132.00, RM1.50 adalah yuran gateway. Model ini perlu **eksplisit** dalam sistem baru (`payment.grossAmount`, `payment.feeAmount`, `payment.netAmount`), bukan tersirat sebagai "beza yang kita abaikan".

---

## 3. Contoh Kes Sebenar

### Kes A — Gandaan ×10 dengan nilai bocor silang (paling serius)

Dua transaksi, **saat yang sama**, SP berbeza:

```
2026-02-28 11:48:44
  002M2X6U      bill RM150.00     → resit RM2,725.00   (×18.17 ??)
  MY0000902X6V  bill RM272.50     → resit RM2,725.00   (×10 tepat)
```

`MY0000902X6V` menerima gandaan ×10 (272.50 → 2725.00). Tetapi `002M2X6U` — transaksi **berbeza, SP berbeza, pelanggan berbeza** — menerima **nilai yang sama persis**.

Ini bukan dua silap berasingan. Ini **satu nilai yang bocor ke transaksi lain**.

Kesan pada akaun `002M0005n` (pelanggan bayar RM150):
- Resit dicatat RM2,725
- RM915 di-agih ke 7 invois
- RM1,810 tergantung sebagai kredit
- `bal_amt` akaun jadi **−RM2,410** (sistem fikir pelanggan ada kredit RM2,410)

### Kes B — Nilai bocor tanpa gandaan

```
2024-10-28 12:35:38   001Y1YRA  bill RM180.00  → resit RM181.10
2024-10-28 12:35:39   001T1YRB  bill RM110.00  → resit RM181.10
```

Dua SP berbeza, satu saat berbeza, **amaun resit identik**. Transaksi kedua sepatutnya RM110 (atau RM108.50 selepas yuran).

### Kes C — Corak sama, tiga bulan kemudian

```
2025-04-06 15:45:21   001I29YN  bill RM159.50  → resit RM2,593.50
2025-04-06 15:45:21   001D29YO  bill RM200.00  → resit RM2,595.00
```

Saat yang sama, dua SP, dua nilai gila yang **berbeza tepat RM1.50** (yuran transaksi). Nilai teras yang sama, satu dengan yuran ditolak.

### Kes D — Amaun resit dikira dari baki invois (mekanisme berbeza)

```
2026-07-21 14:56:29   001138Z4  bill RM133.50  → resit −RM1.37
```

Pelanggan (JASBAR SINGH) bayar RM133.50 melalui FPX. Bank debit berjaya (`fpx_debitAuthNo 9398044149`, `debitAuthCode 00`, mpay status `S`).

Resit yang tercipta: **negatif RM1.37**.

Punca dijumpai dengan mengira lapan invois dalam bill:

```
SUM(invoice.amt_)     = RM132.00
SUM(invoice.amt_actv) = RM133.37   (termasuk penalti RM1.37 pada satu invois)
                        ─────────
132.00 − 133.37       = −RM1.37    ← TEPAT sama dengan amaun resit
```

Sistem mengira amaun resit sebagai **`SUM(amt_) − SUM(amt_actv)`** — iaitu jumlah penalti terkumpul — bukan amaun yang sebenarnya dibayar.

**Kesan:** kelapan-lapan invois kekal `amt_actv` penuh. Dari pandangan sistem, pelanggan **belum bayar apa-apa**. Penalti akan terus terkumpul atas hutang yang sudah dijelaskan.

### Kes E — Tambahan kecil (~0.1%) — punca TIDAK DIKETAHUI

```
bill 360.00 → resit 360.36   (+0.36)
bill 240.00 → resit 240.24   (+0.24)
bill 180.00 → resit 180.18   (+0.18)
bill 142.00 → resit 142.14   (+0.14)
bill 320.00 → resit 320.20   (+0.20)   ← tak padan 0.1%
bill 122.00 → resit 122.22   (+0.22)   ← tak padan 0.1%
```

Kebanyakan ≈ 0.1% tetapi tidak konsisten. **Kami tidak dapat menentukan puncanya** dari data sahaja. Direkod di sini supaya team baru sedar ia wujud.

---

## 4. Finding

### 4.1 Punca A: Shared mutable state dalam callback handler (bukti kuat)

**Bukti:** tiga pasangan berasingan (Feb 2026, Okt 2024, Apr 2025) di mana dua transaksi pada **saat yang sama** menghasilkan amaun resit yang tercemar silang, merentasi SP dan pelanggan berbeza.

Nilai amaun satu transaksi ditulis ke resit transaksi lain. Ini hanya mungkin jika handler menyimpan nilai dalam state yang **dikongsi antara request serentak** — contohnya:
- Field statik
- Field boleh-ubah pada bean singleton
- Formatter/converter yang dikongsi dan tidak thread-safe

**Ini juga menerangkan kejarangannya:** ia memerlukan dua callback tiba dalam saat yang sama. Pada volume ~15,000 transaksi/tahun, perlanggaran berlaku sekali-sekala sahaja.

**Gandaan ×10/×100** kemungkinan sebahagian mekanisme yang sama — jika pembahagi penukaran sen→ringgit juga dikongsi, satu thread boleh menggunakan nilai thread lain.

### 4.2 Punca B: Amaun resit dikira, bukan diambil (bukti kuat)

Kes D menunjukkan wujudnya jalan kod yang mengira amaun resit dari **keadaan dalaman** (baki invois) dan bukan dari **fakta luaran** (bayaran yang disahkan gateway).

Ini salah secara asasnya. Bank sudah mendebit pelanggan; amaun itu adalah fakta yang tidak boleh dipertikaikan. Mengira semula dari baki invois memperkenalkan kebergantungan pada keadaan yang boleh berubah antara masa bill dicipta dan masa callback tiba.

### 4.3 Punca C: Tiada reconciliation automatik (disahkan)

Kes `002M2X6U` (RM2,575 wang hantu) berlaku **28 Februari 2026** dan hanya ditemui **21 Julai 2026** — 5 bulan kemudian, secara kebetulan semasa siasatan lain.

Tiada job atau alert yang membandingkan bayaran gateway dengan resit yang dicipta.

### 4.4 Nota: Kekeliruan unit sen vs ringgit

- `mpay_ol_pymt_txn.amt_charged` — **sen** (13350 = RM133.50)
- `pay_bill.amt` — **sen**
- `mon_sp_fi_doc.amt_` — **ringgit** (decimal)

Penukaran dibuat secara manual pada setiap titik integrasi. Satu penukaran terlepas = silap 100×. Walaupun ini mungkin bukan punca utama, ia **memperkuat** kesan bug lain (silap kecil jadi silap besar).

---

## 5. Cadangan Solution untuk Monthley Baru

### Guard 1 — Amaun resit MESTI datang dari gateway, jangan dikira

```java
// BETUL
receipt.setAmount(verifiedPayment.getNetAmount());

// SALAH — jangan sesekali
receipt.setAmount(invoices.sumAmount().subtract(invoices.sumActiveAmount()));
```

**Prinsip:** bayaran adalah **fakta luaran** (bank sudah debit). Baki invois adalah **keadaan dalaman** yang boleh berubah. Resit mesti merekod fakta, bukan mengira semula dari keadaan.

Ini menghapuskan Kes D sepenuhnya.

### Guard 2 — Callback handler MESTI stateless

- Tiada field boleh-ubah pada bean/service yang mengendalikan callback
- Semua konteks transaksi dalam parameter method atau local variable
- Jika perlu formatter/converter, cipta baru per-request atau gunakan yang thread-safe

**Ujian:** jalankan 100 callback serentak dengan amaun berbeza; sahkan setiap resit padan dengan bayarannya. Jadikan ini ujian integrasi kekal.

Ini menghapuskan Kes A, B, C.

### Guard 3 — Satu jenis `Money` merentasi seluruh sistem

```java
public record Money(long minorUnits, Currency currency) { }
```

- Semua amaun dalam sistem menggunakan jenis ini
- Penukaran hanya di **sempadan I/O**, sekali, di satu tempat
- Compiler menghalang percampuran sen dan ringgit

Ini menghapuskan seluruh kelas silap ×10/×100.

### Guard 4 — Idempotency pada `merchant_txn_ref`

```sql
UNIQUE (gateway_txn_ref)
```

Payment gateway akan **retry callback** jika tidak menerima respons 200 OK. Tanpa guard, retry mencipta resit kedua.

Callback kedua dengan ref yang sama → kembalikan hasil asal, jangan cipta baru (corak Stripe).

### Guard 5 — Model yuran secara eksplisit

```java
class Payment {
    Money grossAmount;   // apa yang pelanggan bayar (dari gateway)
    Money feeAmount;     // yuran transaksi (1.50 / 2.00 / 0)
    Money netAmount;     // apa yang SP terima → jadi amaun resit
}
```

Invariant: `grossAmount = feeAmount + netAmount`

Dalam legacy, yuran wujud sebagai "beza yang kita jangka" — menyukarkan pengesanan anomali kerana kita perlu tahu senarai beza yang "OK". Model eksplisit menjadikan sebarang penyimpangan jelas serta-merta.

### Guard 6 — Reconciliation automatik (harian)

Job berjadual yang membandingkan setiap bayaran gateway berjaya dengan resit dalam sistem:

```sql
-- Konsep; dalam sistem baru guna satu DB atau API gateway
SELECT p.gateway_ref, p.net_amount, r.amount,
       p.net_amount - r.amount AS diff
FROM   payments p
LEFT JOIN receipts r ON r.gateway_ref = p.gateway_ref
WHERE  p.status = 'SUCCESS'
  AND  (r.id IS NULL OR ABS(p.net_amount - r.amount) > 0.005)
```

Alert jika:
- Bayaran berjaya **tanpa** resit
- Amaun resit ≠ amaun bersih bayaran
- Resit **tanpa** bayaran sepadan

> Ini guard paling murah dan paling berbaloi. Ia juga patut dilaksanakan pada **legacy sekarang** — kes RM2,575 tidak akan terlepas 5 bulan jika ada.

### Guard 7 — Ledger sebagai satu sumber kebenaran

Rujuk CASE-002 untuk konteks penuh. Ringkasnya: dalam legacy, baki disimpan di **empat tempat** (`bal_amt`, `amt_actv`, running balance dalam txn, doc_link) yang boleh menyimpang antara satu sama lain.

Untuk payment online: satu bayaran yang salah dicatat akan merosakkan kesemua empat, dan pembetulan memerlukan tool khas yang memainkan semula ledger.

Dalam sistem baru — **satu event ledger, semua baki di-derive on-read** — satu pembetulan pada event membetulkan segala-galanya.

---

## 6. Senarai Semak Sebelum Go-Live

- [ ] Ujian beban: 100+ callback serentak, sahkan setiap resit padan bayaran
- [ ] Ujian idempotency: hantar callback yang sama 5×, sahkan hanya satu resit
- [ ] Ujian unit: sahkan tiada penukaran sen↔ringgit manual di luar sempadan I/O
- [ ] Ujian penalti: bayar invois yang mempunyai penalti; sahkan amaun resit = bayaran, bukan penalti
- [ ] Reconciliation job berjalan dan beralert sebelum go-live, bukan selepas
- [ ] Code review khusus: cari field boleh-ubah pada mana-mana bean dalam laluan payment
- [ ] Dokumen struktur yuran (1.50 / 2.00 / 0) sebagai peraturan perniagaan eksplisit

---

## 7. Isu Terbuka

| # | Isu | Catatan |
|---|---|---|
| 1 | Punca tambahan ~0.1% (Kes E) | Tidak dapat dijelaskan dari data. Perlu semakan kod. |
| 2 | Pengesahan kod callback legacy | Semua analisis di atas adalah **inferens dari data**. Untuk pengesahan, semak: (a) ada tak field statik/instance boleh-ubah? (b) amaun resit dari mana? (c) ada tak lock/synchronized? |
| 3 | SP `001I` muncul 3× dalam senarai anomali | Semua bill RM159.50. Mungkin ada faktor khusus SP. |
| 4 | `002I31AE` — satu `pymt_ref_no`, dua resit (19 & 21 Apr) | Perlu semakan berasingan. |
| 5 | Baki tergantung selepas pembatalan | Bila resit salah dibatalkan, kredit tergantung mungkin kekal. Perlu prosedur pembersihan. |

---

## 8. Ringkasan Satu Muka Surat

**Apa yang berlaku:** Amaun resit tidak sepadan amaun dibayar. 33 kes / 18 bulan / ~20,885 transaksi.

**Kenapa:**
1. Shared mutable state dalam callback handler → nilai bocor antara transaksi serentak
2. Amaun resit dikira dari baki invois, bukan diambil dari gateway
3. Tiada reconciliation → satu kes terlepas 5 bulan

**Kesan terburuk:** RM310,000 dicatat untuk bayaran RM310. Wang hantu dalam buku SP.

**Guard utama untuk sistem baru:**
1. `receipt.amount := gateway.netAmount` — ambil, jangan kira
2. Callback handler stateless — tiada state dikongsi
3. Satu jenis `Money`, tukar unit sekali di sempadan
4. Idempotency key pada `gateway_txn_ref`
5. Model yuran eksplisit (gross/fee/net)
6. Reconciliation job harian dengan alert
7. Ledger sebagai satu sumber kebenaran

---

*Disediakan Julai 2026 daripada siasatan production legacy. Semua contoh adalah data sebenar (rujukan pembayaran dikekalkan untuk jejak audit).*
