# ADR 0009 — Satu Takrifan Baki + Guna Advance

- **Status:** Dicadang
- **Tarikh:** 24 Julai 2026

## Konteks

Advance tercipta tetapi tiada kod yang memakainya (RCP000005: nilai 500,
dialokasi 300, baki advance 200 tergantung). Semasa menyiasat, kami
mendapati masalah yang lebih besar: **baki dikira dengan formula berbeza
di tempat berbeza, dan formula itu tidak bersetuju.**

### Bukti penyimpangan (M04, 24 Julai 2026)
| Sumber | Formula | Hasil |
|---|---|---|
| Senarai Akaun | debit − resit | **590.59** |
| Dashboard | debit − alokasi | **700.59** |
| Betul | debit − (resit + kredit nota) | **500.59** |

Setiap satu terlepas sesuatu yang berbeza:
- Senarai Akaun abaikan **kredit nota RM90** — pelanggan diberi kredit,
  baki tidak turun
- Dashboard abaikan **advance RM200** — pelanggan sudah bayar lebih,
  baki tidak turun

### Kenapa ia tidak disedari

M01 memberi jawapan SAMA untuk ketiga-tiga formula (1,050.59) kerana ia
tiada kredit nota dan tiada advance. Penyimpangan hanya muncul apabila
salah satu keadaan itu wujud — senyap sehingga ia berlaku, kemudian
memberi nombor salah tanpa sebarang ralat.

Corak yang sama dengan CASE-001 dan CASE-003: keadaan jarang, nombor
salah, tiada amaran. Dan corak yang sama dengan CASE-002 sendiri —
baki disimpan di beberapa tempat lalu menyimpang. Kami menulis dokumen
tentangnya, kemudian melakukannya.

---

## Keputusan

### 1. Satu takrifan baki, satu tempat
**Alokasi TIDAK muncul dalam kiraan baki.** Alokasi ialah untuk padanan —
resit mana membayar invois mana — bukan untuk jumlah baki.

Ini menjadikan baki teguh: alokasi yang tersilap, terbalik, atau tertinggal
tidak boleh merosakkan baki. Itu tepat kegagalan CASE-001 (alokasi yatim
daripada resit dibatalkan menghanyutkan baki).

Dilaksana sekali sahaja dan dikongsi — bukan disalin ke setiap pemanggil.

### 2. Advance jadi automatik

Dengan takrifan di atas, advance tidak perlukan logik khas:

| Keadaan | Kiraan | Hasil |
|---|---|---|
| Resit 880, tiada invois | 0 − 880 | **−880** (kredit) |
| + invois 80 | 80 − 880 | **−800** |
| + invois 80 | 160 − 880 | **−720** |

Sepadan dengan penyata legacy M0318 (2025): baki (880.00) menyusut setiap
bulan hingga 0.00 pada Disember. Tingkah laku yang sama, tanpa penunjuk
boleh-ubah atau baki berjalan yang di-cache.

**Baki boleh negatif.** Negatif bermakna pelanggan ada kredit.

### 3. Tiga soalan, tiga sumber — jangan campur

| Soalan | Sumber | Kredit nota dikira? |
|---|---|---|
| Berapa duit masuk? | jadual `payment` | **Tidak** — bukan duit |
| Berapa pelanggan berhutang? | dokumen: debit − kredit | **Ya** — hutang berkurang |
| Invois mana dibayar oleh apa? | `fi_allocation` | **Ya** — ia padanan |

Contoh: kutipan RM900 + kredit nota RM100. Dashboard betul melaporkan
kutipan RM900 (duit sebenar masuk bank). Baki turun RM1,000 — RM900
daripada duit, RM100 daripada kredit. Kedua-duanya betul.

Bahaya jika dicampur: kredit nota dikira sebagai kutipan bermakna SP
nampak duit yang tidak pernah masuk. Itu antara punca CASE-003.

Disahkan sihat pada 24 Julai 2026: `payment` dan dokumen RECEIPT sepadan
sempurna (12 baris, RM2,441.68 kedua-duanya), dan sifar baris payment
untuk kredit nota.

### 3b. Kod warna baki

| Baki | Maksud | Warna |
|---|---|---|
| Positif | Ada hutang | Merah |
| Sifar | Lunas | Hijau / lembut |
| Negatif | Ada kredit | Hijau / lembut |

Merah untuk baki sifar atau negatif bercanggah — SP nampak amaran
sedangkan keadaan baik.

Semakan 24 Julai: frontend sebahagian besarnya SUDAH bersedia (kad baki
my-accounts guna kelas .neg dan kurungan; penyata hijau bila negatif;
jumlah tunggakan guna Math.max(0, balance) supaya kredit tidak mengurangkan
jumlah hutang). Satu tempat menganggap baki sentiasa hutang: manual-payment
senarai akaun, warna merah hardcoded.

### 4. Invariant sisi kredit (baharu)

`AllocationGuard` sekarang hanya menjaga sisi debit — invois tidak boleh
menerima alokasi melebihi nilainya. Tiada apa yang menghalang sisi kredit.

Sebaik advance di-knock automatik, satu resit akan dialokasi merentas
BANYAK invois sepanjang beberapa bulan. Tanpa invariant, satu pepijat dalam
gelung knock boleh mengalokasi resit RM500 sebanyak RM800 — dan tiada apa
yang menangkapnya.
Advance baki = nilai resit − SUM(alokasi darinya). Diterbitkan, bukan
disimpan — tiada lajur baharu.

### 5. Advance di-knock semasa jana bil

Mengikut legacy, dan ia betul: invois baharu dicipta, advance yang ada
terus di-knock. Pelanggan nampak baki menyusut tanpa perlu buat apa-apa.

Penjanaan invois menjadi laluan KELIMA yang mencipta alokasi. Ia MESTI guna
`LineAllocationWriter` sedia ada, bukan menulis logik sendiri —
`cara-kerja.md` guard 6.

---

## Fasa pelaksanaan

| Fasa | Kandungan | Kenapa urutan ini |
|---|---|---|
| P1 | Satu takrifan baki, dikongsi (`AccountBalance`) | Penyimpangan SUDAH wujud hari ini — dua skrin memapar nombor berbeza |
| P2 | Invariant sisi kredit + ujian sengaja langgar | Jaring keselamatan SEBELUM knock automatik |
| P3 | Knock advance semasa jana bil, guna `LineAllocationWriter` | Guard 6 |
| P4 | Papar kredit dalam UI (baki negatif, penyata) | Kosmetik, selepas nombor betul |

P1 dahulu kerana penyimpangan bukan risiko masa depan — ia berlaku sekarang.

## Risiko & mitigasi

| Risiko | Mitigasi |
|---|---|
| Baki berubah untuk akaun sedia ada | Dijangka — itulah pembetulannya. Rakam sebelum/selepas macam backfill P4 ADR 0006 |
| Kod menganggap baki >= 0 | Cari perbandingan dan pemformatan yang mengandaikan positif |
| Knock berulang mengalokasi lebih | Invariant kredit (P2) menghalang |
| Alokasi lama tidak sepadan baki baharu | Tidak berkaitan — baki tidak lagi bergantung pada alokasi |

## Alternatif ditolak

- **Simpan `advance_remaining` sebagai lajur** — cache keempat yang boleh
  hanyut. Advance boleh diterbitkan; menyimpannya mengulangi CASE-002.
- **Kekalkan baki = debit − alokasi** — bermakna advance yang belum
  dialokasi tidak pernah dikira, dan baki tidak boleh negatif. Bercanggah
  dengan tingkah laku legacy yang SP jangkakan.
- **Betulkan setiap query baki di tempat masing-masing** — itu punca
  masalah asal.

## Rujukan
- `evidence/CASE-001-balance-mismatch-A0124.md`
- `evidence/CASE-002-amt_actv-scenario-catalog.md` (baki empat tempat)
- `0006-line-level-allocation-plan.md` (LineAllocationWriter)
- `cara-kerja.md` §4b guard 6 (satu keputusan, satu tempat)
