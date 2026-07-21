# ADR 0003 — Account Adjustment (Debit Note / Credit Note)

- **Status:** Diterima
- **Tarikh:** 22 Julai 2026
- **Konteks:** Skrin Account Adjustment (ADD/REDUCE) menyasar invois tertentu.
  frontend-contract.md tanda ini "laluan ketiga family 2 (CASE-001)" dengan
  soalan terbuka: `ADJ_ADD/REDUCE -> J00 atau JNL?`. Adjustment adalah punca
  utama drift baki di legacy (baki ≠ amount aktif).

---

## Masalah

Legacy: "amount aktif ≠ baki" — sebahagian besar berpunca dari adjustment.
accounting-invariants.md mengesan **empat family** punca drift:

1. **Over-allocation** — `SUM(alokasi) > amount`, tiada validation masa write
   + race condition (semak-lepas-baca tanpa kunci).
2. **Laluan tak kunci** — satu laluan allocation ingat kunci, satu lupa
   ("J00 hantu dari laluan lain").
3. **Dokumen mutable** — `amt_` invois diubah SELEPAS allocation → invariant
   runtuh (invois RM350 di-allocate penuh, amaun edit jadi RM300).
4. **Double-submit** — dua adjustment saat sama, ref berturutan (CASE-001).

Reka bentuk baru MESTI elak keempat-empatnya dari akar, bukan tampung.

---

## Keputusan

Adjustment = **dokumen baru** (bukan mutate invois, bukan journal telanjang).
Dua jenis dokumen baharu ditambah ke `DocumentType`:

### Reduction → CREDIT_NOTE (kredit nota)

Kurangkan tunggakan pelanggan (waiver, diskaun selepas invois, silap caj lebih).

- Cipta dokumen `CREDIT_NOTE`.
- Cipta `fi_allocation` menyasar invois pilihan (debit_document = invois,
  credit_document = kredit nota) — sama mekanik knock resit.
- Ledger: **Dr Hasil / Cr AR** (SourceType.ADJUSTMENT).
- Baki turun kerana baki = invois − Σ(alokasi aktif). Invois asal TAK disentuh.

### Additional → DEBIT_NOTE (debit nota)

Tambah tunggakan pelanggan (caj tambahan, silap caj kurang).

- Cipta dokumen `DEBIT_NOTE` — masuk kiraan baki macam invois (doc debit).
- TIADA allocation (adjustment tambah tak boleh jadi alokasi — alokasi hanya
  mengurang).
- Ledger: **Dr AR / Cr Hasil** (SourceType.ADJUSTMENT).
- Invois asal TAK disentuh.

---

## Bagaimana ia elak setiap family

| Family | Punca | Guard dalam reka bentuk ini |
|---|---|---|
| 1. Over-allocation | tiada invariant masa write | Reduction: invariant `SUM(alokasi aktif) + amt <= invois.amount` DIKUATKUASA masa write (accounting-invariants §3). |
| 1b. Race | semak-lepas-baca tanpa kunci | **Kunci pesimis** pada invois sasaran: `em.find(FinancialDocument, id, PESSIMISTIC_WRITE)` sebelum semak invariant. Semua alokasi ke dokumen itu bersiri. |
| 2. Laluan tak kunci | invariant per-laluan, satu lupa | Invariant + kunci diletak dalam SATU komponen domain (`AllocationGuard`) yang dikongsi SEMUA laluan (bayaran/knock manual/auto-knock/Reduction). Bukan disalin per-laluan. |
| 3. Dokumen mutable | `amount` diubah selepas alokasi | `document.amount` **immutable selepas dipos**. Additional = DEBIT_NOTE baru, bukan edit invois. Nak ubah invois? Contra + terbit semula. |
| 4. Double-submit | dua submit saat sama | **Idempotency token** dari klien (UUID) disimpan pada dokumen (`source_ref`). Submit kedua dengan token sama ditolak. |

---

## Implikasi / Kerja

Migration (V28):
- `DocumentType` + `CREDIT_NOTE`, `DEBIT_NOTE` (lebar kolum doc_type cukup).
- `financial_document.source_ref` (idempotency token, unik per SP).

Domain:
- `AllocationGuard` — komponen tunggal: kunci pesimis + invariant
  `SUM(alokasi aktif) + amt <= doc.amount`. Kongsi dgn FifoAllocator/PaymentService.
- `AdjustmentService` — cipta CREDIT_NOTE (+alokasi via guard) atau DEBIT_NOTE,
  post ledger (SourceType.ADJUSTMENT), token idempotency.
- Endpoint `POST /accounts/{id}/adjustment` (peranan: SP_ADMIN).

Baki kekal diterbitkan SUM (billing-rules §9). CREDIT_NOTE muncul dalam
statement sebagai baris kredit; DEBIT_NOTE sebagai baris debit.

**Data lama (387 kes over-allocation)** = kerja migrasi berasingan, bukan
skop ADR ini (accounting-invariants: "Data lama = cleanup. Berasingan").

---

## Rujukan

- `accounting-invariants.md` §3 (invariant + kunci pesimis, empat family)
- `evidence/CASE-001-balance-mismatch-A0124.md` (bukti drift production)
- `billing-rules.md` §9 (baki diterbitkan)
- `frontend-contract.md` §Adjustment (soalan terbuka — kini dijawab: doc baru)
