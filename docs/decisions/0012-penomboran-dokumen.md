# ADR 0012 — Penomboran dokumen membaca tetapan SP

- **Status:** Diterima (28 Julai 2026). BELUM DILAKSANA.
- **Berkait:** CASE-008 kes 5, soalan terbuka 17

## Konteks

SP menetapkan prefix, saiz dan nombor mula dalam Tetapan Invois dan
Tetapan Resit. SP0002 telah mengisi `I26` / 7 digit dan `R26` / 6 digit.

Dokumen keluar sebagai `INV000099` dan `RCP000034`.

`DocumentNumberService.next()` membaca `document_number_sequence`, yang
mempunyai lajur prefix dan padding SENDIRI. Tetapan tidak pernah dibaca.
Nilai lalai `INV` / `RCP` / `CN` / `DN` ditulis semasa baris turutan
dicipta, dan tidak pernah disemak semula.

Dua sumber kebenaran untuk satu keputusan (guard 6).

## Bagaimana legacy melakukannya

Daripada `InvoiceGenerator.java` baris 336-338 dan 455-462:

    long invCurrCount = docQuery.getDocumentCount(spCode, "INV");
    String refNoFormat = docServ.getDocumentRefNoFormat(sp, "INV");
    long refNoSerial = (sp.getInvoiceNoStart() == null ? 0
                        : sp.getInvoiceNoStart()) + invCurrCount;

    while (docQuery.isDocumentExists(spCode, "INV",
                                     String.format(refNoFormat, refNoSerial))) {
        ++refNoSerial;
    }

**Tiada kaunter disimpan.** Nombor dikira daripada COUNT dokumen sedia
ada, setiap kali.

Dua tanda ia rapuh, kedua-duanya kelihatan dalam kod:

1. **Gelung anti-tembung wujud kerana ia diperlukan.** Padam satu
   dokumen dan COUNT jatuh; nombor seterusnya bertembung. Gelung itu
   tampalan untuk kelemahan formula.
2. **Baris 356:** `else refNoSerial--; // reclaim the ref no if not used`
   — nombor diurus dengan tangan.

Di bawah larian serentak, dua thread mendapat COUNT yang sama.

`financial_document` legacy tiada kekangan UNIQUE pada nombor dokumen;
gelung itu menyemak secara manual apa yang pangkalan data sepatutnya
jamin.

## Apa yang kita sudah ada dan lebih baik

`DocumentNumberService` menggunakan `SELECT ... FOR UPDATE` pada baris
turutan. Tiada lubang race, tiada gelung, tiada penuntutan semula.

`uk_doc_no (sp_code, doc_no)` ialah UNIQUE. Pendua MUSTAHIL — pangkalan
data menolaknya.

Yang hilang hanya satu: prefix dan padding tidak dibaca daripada
tetapan.

## Keputusan

**1. Tetapan ialah niat; turutan ialah keadaan berjalan.**

    sp_document_setting        prefix, no_size, no_start  — niat SP
    document_number_sequence   next_value, prefix_terakhir — keadaan
    uk_doc_no                  tiada pendua                — jaminan

`next()` membaca prefix dan padding daripada `sp_document_setting`
setiap kali. Baris turutan menyimpan `next_value` sahaja, ditambah
prefix yang terakhir digunakan.

**2. Prefix berubah → turutan RESET ke `no_start`.**

    prefix INV, next 99   ->  INV000099
    SP tukar ke I26       ->  reset, I260000001

Legacy tidak pernah reset kerana formula COUNTnya tidak boleh. Ini
keputusan baharu, disahkan pemilik produk: prefix menandakan tahun
(`K19` = 2019 dalam data produksi), dan setiap tahun bermula semula.

**3. Tembung dikendalikan dengan RETRY, bukan gelung semakan.**

Menukar prefix BALIK kepada yang pernah digunakan menghasilkan nombor
yang sudah wujud:

    INV -> I26 -> INV    (SP tersilap taip dan membetulkannya)

Jarang, tetapi mungkin. `uk_doc_no` menolak INSERT; `next()` menaikkan
`next_value` dan mencuba semula, terhad kepada bilangan percubaan yang
munasabah.

Kita TIDAK menyalin gelung `isDocumentExists` legacy: menyemak sebelum
menulis mempunyai lubang race yang tepat sama seperti COUNT. Kekangan
menolak, kita bertindak balas.

**4. Nilai lalai kekal untuk SP yang tidak menetapkan apa-apa.**

`INV` / `RCP` / `CN` / `DN`, padding 6. SP yang tidak pernah membuka
skrin tetapan mendapat tingkah laku semasa.

## Kesan pada data sedia ada

Dokumen sedia ada TIDAK dinamakan semula. SP0002 akan mempunyai
`INV000001`–`INV000099` diikuti `I260000001`. Itu dijangka: prefix
menandakan tempoh, dan tempoh berubah.

## Pelaksanaan

- V41 — `document_number_sequence.last_prefix VARCHAR(10)`
- `next()` membaca tetapan; bandingkan dengan `last_prefix`; reset jika
  berbeza
- Retry pada `DataIntegrityViolationException` daripada `uk_doc_no`
- Ujian: prefix berubah reset; prefix sama berterusan; tukar balik
  kepada prefix lama tidak menghasilkan pendua; SP tanpa tetapan
  mendapat lalai

## Rujukan
- CASE-008 kes 5
- InvoiceGenerator.java (legacy) baris 336-338, 356, 455-462
