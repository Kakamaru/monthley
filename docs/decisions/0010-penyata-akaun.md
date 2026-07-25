# ADR 0010 — Penyata akaun: satu perkhidmatan, baris ikut dokumen

- **Status:** Diterima (25 Julai 2026) — P1 disahkan, P2-P6 belum dilaksana
- **Berkait:** ADR 0006 (alokasi peringkat line), ADR 0009 (satu takrifan baki)
- **Bukti:** evidence/CASE-004-ledger-line-taxonomy.md

## Konteks

Legacy menjana penyata melalui Pentaho Reporting yang dipasang berasingan
pada pelayan. Templat m_statement_dark.prpt telah dibongkar dan SQL-nya
diperiksa. Terdapat TIGA pintu masuk: ikon pada setiap akaun, portal
pelanggan, dan tab Laporan (Customer Account Statement).

Penyata membaca mon_fi_doc_txn (ledger) secara terus melalui JNDI
datasource di dalam templat. Satu baris ledger sama dengan satu baris
penyata.

### Lima pepijat legacy yang ditemui dalam templat

**L1 — Baris "Previous Balance" mencetak nombor yang salah.**
UNION pertama mengambil transaksi terakhir tahun sebelum
(ORDER BY txn_id DESC LIMIT 1), lalu mencetak amaun transaksi itu
dalam lajur Amount, sedangkan lajur Balance mengambil snapshot baki.
Pada SRIAH 01004 ini menghasilkan baris "Previous Balance, 2025"
bernilai (130.00) bersebelahan baki 0.00 — RM130 itu ialah resit
terakhir 2025, bukan baki bawa hadapan. Kotak ringkasan betul; barisnya
yang salah.

**L2 — Lajur Balance ialah cache tersimpan.**
Ia membaca dt_acc_amt / cr_acc_amt, iaitu snapshot baki berjalan dalam
ledger — medan yang sama yang FixAccountGLM.java fasa 1 betulkan.
Penyata mewarisi setiap penyimpangan. Due Amount pula datang terus
daripada mon_sp_acc.bal_amt, cache yang ADR 0009 sudah tolak.

**L3 — "Sila Pilih" ialah kemalangan.**
Tapisan tahun ialah LIKE CONCAT(parameter,'%'). Apabila parameter
kosong ia menjadi LIKE '%' iaitu semua rekod, manakala dua UNION lain
(= parameter-1 dan = parameter+1) menjadi = -1 dan tiada baris. Itu
sebab Pandan Mewah memaparkan "Statement Of : Year" kosong DAN tiada
baris Previous Balance. Dua gejala, satu punca.

**L4 — Tahun berikutnya bocor masuk.**
UNION ketiga menarik semua transaksi tahun+1. Invois yang dijana awal
untuk tahun hadapan muncul dalam penyata tahun semasa.

**L5 — Query tidak boleh guna index.**
Join ialah ON (acc_id = cr_acc_id OR acc_id = dt_acc_id) — OR dalam
syarat join mematikan index, diulang tiga kali. Tambah
SUBSTRING(fi_period,1,4) = ... (fungsi atas lajur) dan UNION dan bukan
UNION ALL (dedupe sort). Punca 10 saat ialah query, bukan enjin.

### Baris "Advanced Payment"

Ia rekod sebenar, bukan sintesis: baris ledger txn_code M2000 dengan
prod_id NULL, mewakili baki resit yang belum dialokasi pada saat resit
dicipta. 33,595 baris, tepat satu setiap resit.

Legacy memerlukannya kerana susun atur ikut baris ledger tetapi lajur
baki bergerak ikut dokumen; baris itu jambatan antara dua pandangan.
Bawah ADR 0009 jurang itu tidak wujud, jadi jambatan tidak diperlukan.

## Keputusan

**1. Satu StatementService, tiga pemanggil.** Ikon akaun, portal
pelanggan dan tab Laporan memanggil perkhidmatan yang sama dengan
parameter berbeza. Tiada query penyata di luar perkhidmatan ini.

**2. Penyata tidak menyoal pangkalan data sendiri.** Tiada enjin luar,
tiada JDBC dalam templat. Templat menerima model yang sudah siap
dikira. Guard 6 diterjemah kepada pelaporan. (Menghalang L1, L3, L4 —
kesilapan menjadi ujian yang gagal, bukan aduan SP.)

**3. Baris ikut dokumen.** Satu baris per dokumen kewangan; lajur baki
digerakkan oleh dokumen sahaja, dikira dengan window function
SUM(signed_amount) OVER (ORDER BY doc_date, id). Tidak sekali-kali
membaca baki tersimpan. (Menghalang L2.)

Lajur baki menghasilkan angka yang SAMA dengan legacy pada setiap
sempadan dokumen — legacy pun menggerakkan baki ikut dokumen; barisan
ledger cuma memecahkannya. SP tidak melihat sebarang nombor berubah.

**4. Padanan menjadi detail.** Alokasi dirender sebagai sub-baris
berinden dengan nombor invois — termasuk di portal pelanggan. Sub-baris
TIDAK menyentuh lajur baki.

**5. Baki Bawa Ke Hadapan daripada VIEW account_balance bertarikh.**
Dokumen sebelum tarikh mula tempoh. Bukan lajur tersimpan, bukan
formula baharu, bukan "transaksi terakhir tahun lepas". Medan cache
tidak boleh menjawab soalan sejarah: amt_actv hanya tahu keadaan hari
ini, jadi penyata 2021 akan bercakap tentang 2026.

**6. Penyata ikut tahun menjadi lalai; tiada had keras.** Tahun yang
dipilih mesti keluar sepenuhnya. Ambang diletak pada BILANGAN BARIS,
bukan muka surat atau tahun — satu tahun pun boleh berjela apabila
invois dijana, dibatalkan sebahagian, dijana semula. Bawah ambang jana
terus; atas ambang jana tak segerak dan hantar pautan e-mel. Tiada apa
ditolak. Nilai ambang ditetapkan selepas ukuran P3.

**7. Dua penulis atas satu model.** StatementPdfWriter dan
StatementXlsxWriter menerima StatementModel yang sama. XLSX BUKAN
laluan kod kedua.

**8. Excel dirender rata, dua sheet.** Sub-baris tidak boleh digunakan
dalam Excel — sekali pengguna sort, anak terpisah daripada induk.
Sheet "Transaksi" (satu baris per dokumen, ada baki) dan sheet
"Padanan" (satu baris per alokasi, ada resit_no + invois_no, tiada
baki). Sheet Padanan menjawab "resit mana bayar invois mana" melalui
pivot.

**9. Tunggakan dan Baki dinamakan berasingan.** Tunggakan tidak boleh
negatif; Baki boleh. Legacy memaparkan Due Amount bersebelahan Total
tanpa membezakannya.

**10. Dokumen batal kekal dipaparkan** dengan legend aktif/batal.
Ledger append-only; pembatalan ialah contra, bukan padam. doc_reason
dirender sebagai baris nota italic di bawah keterangan.

**11. Jenis baris eksplisit, bukan teks bebas.** fi_document_line
membawa line_type (PRODUCT, PENALTY, ADVANCE, ADJUSTMENT,
OPENING_BALANCE), product_id (NULL selain PRODUCT), period_start /
period_end sebagai DATE, dan description sebagai snapshot sejarah yang
dipapar tetapi tidak pernah disoal.

Baris ADVANCE tidak wujud dalam skema baharu: lebihan bayaran ialah
baki negatif yang diterbitkan. Migrasi menggugurkan baris M2000 dan
mengambil mon_sp_fi_doc.amt_ sebagai amaun resit yang authoritative.
Lima resit legacy yang kehilangan baris M2000 sembuh secara automatik
di bawah takrifan ini.

Syarat: dokumen aktif yang SEMUA baris ledgernya batal mesti dianggap
batal semasa migrasi, jika tidak ia menjadi kredit hantu (contoh
R242390, doc A dengan ledger dan link semuanya C).

## Struktur data

    StatementModel
      header          kepala SP, akaun, tempoh
      openingBalance  daripada VIEW account_balance bertarikh
      rows            List<StatementRow>
      closingBalance
      arrears         tunggakan (tidak negatif)

    StatementRow
      docDate, docType, docNo, description, remark
      status          aktif / batal
      amount          bertanda
      runningBalance
      matches         List<StatementMatch>

    StatementMatch
      invoiceNo, productName, period, amount

## Fasa

- **P1** SELESAI (25 Julai 2026). counter(page) dan counter(pages)
  berfungsi dalam kotak margin openhtmltopdf; render satu-pass memadai,
  dua-pass tidak diperlukan. Disahkan oleh
  `com.monthley.statement.PdfPageCounterTest` yang merender 200 baris,
  membaca semula PDF dengan PDFBox, dan membandingkan nombor pada
  setiap muka dengan bilangan muka sebenar.
- **P2** StatementService + StatementModel + query window function.
  Ujian: baki penutup mesti sama dengan VIEW account_balance, dan
  penutup tahun N mesti sama dengan pembukaan tahun N+1.
- **P3** StatementPdfWriter + templat Thymeleaf; kepala dan kaki
  berulang, legend, baris Total pada muka terakhir sahaja. Ukur
  bilangan baris untuk menetapkan ambang keputusan 6.
- **P4** Sambung tiga pemanggil kepada satu perkhidmatan.
- **P5** StatementXlsxWriter dua sheet.
- **P6** Ambang baris + laluan tak segerak.

## Sasaran prestasi

Penyata setahun: satu query bawah 50ms, render bawah satu saat.
FA10-1-10 setahun ialah 20-40 baris, bukan 262.

## Risiko dan mitigasi

| Risiko | Mitigasi |
|---|---|
| SP biasa bentuk lama | Lajur baki menghasilkan angka sama pada setiap sempadan dokumen; hanya bilangan baris berkurang dan nombor invois bertambah |
| counter(pages) tidak disokong | P1 spike sebelum apa-apa kod ditulis |
| Penyata besar | Ambang baris + laluan tak segerak (P6) |
| Pengguna sort Excel | Dua sheet rata, bukan sub-baris |
| Baki pembukaan menyimpang | VIEW yang sama; ujian membandingkan penutup tahun N dengan pembukaan tahun N+1 |
| Migrasi ikut teks | prod_descr tidak boleh dipercayai — M2000 mengandungi kedua-dua "Advanced Payment" dan "Advance Payment". Migrasi ikut txn_code + prod_id sahaja |

## Alternatif ditolak

- **Kekalkan Pentaho** — query duduk dalam fail .prpt yang tidak boleh
  di-grep, di-review, atau masuk mvn test. Lima pepijat L1-L5 hidup di
  sana selama bertahun-tahun tanpa dikesan; itu bukan kebetulan.
  Keperluan sebenar penyata ini ialah kepala berulang dan bilangan muka
  sahaja; tiada header kumpulan, tiada subtotal bersarang. Enjin banded
  tidak diperlukan.
- **JasperReports** — masalah sama, penjual berbeza.
- **Baris ikut baris ledger (bentuk legacy)** — memerlukan baris
  jambatan M2000 untuk merapatkan susun atur dengan lajur baki. Lebih
  banyak kod, dan bercanggah dengan ADR 0009.
- **Ledger sebagai authoritative untuk amaun resit** — lima resit akan
  diimport kurang RM473.55 secara senyap (RM443.00 daripadanya pada
  empat resit yang benar-benar akan diimport; R242390 dikecualikan
  sebagai dokumen batal).
- **Simpan baki pembukaan sebagai lajur** — cache yang boleh hanyut.
  Ulangan CASE-002.
- **Penulis XLSX dengan query sendiri** — dua laluan menyimpang. Punca
  asal masalah ini.

## Nota pelaksanaan (dari P1)

**Pustaka.** `io.github.openhtmltopdf:openhtmltopdf-pdfbox` 1.1.59
(PDFBox 3). Bukan `com.openhtmltopdf`, yang berhenti pada 1.0.10
(September 2021, PDFBox 2). Pakej Java kekal `com.openhtmltopdf.*`
walaupun groupId berpindah.

**Entiti HTML bernama TIDAK disokong.** openhtmltopdf menghurai XHTML
secara ketat; hanya lima entiti terbina XML dibenarkan (`&amp;`,
`&lt;`, `&gt;`, `&quot;`, `&apos;`). `&mdash;`, `&nbsp;`, `&ndash;`,
`&copy;` menyebabkan SAXParseException dan kegagalan render penuh.
Guna aksara Unicode terus. Ini berisiko dalam produksi kerana nama SP
ialah data pengguna — "Maintenance & Sinking Fund" mesti di-escape
betul oleh Thymeleaf, dan `&nbsp;` untuk jarak adalah haram.

**Kepala jadual berulang.** `thead { display: table-header-group; }`
berfungsi tanpa akal-akalan — salah satu keperluan legacy dipenuhi
secara percuma.

**Nota ujian.** Kandungan `:before` dilukis sebagai operasi teks
berasingan, jadi PDFBox mengekstraknya mengikut turutan lukisan
("Page of 1 4") dan bukan turutan visual. Gunakan
`stripper.setSortByPosition(true)` semasa menguji, jika tidak assert
akan gagal walaupun PDF betul.

## Rujukan
- 0009-baki-tunggal-dan-advance.md (VIEW account_balance)
- 0006-line-level-allocation-plan.md (LineAllocationWriter)
- evidence/CASE-004-ledger-line-taxonomy.md
- evidence/CASE-002-amt_actv-scenario-catalog.md
- cara-kerja.md 4b guard 6 (satu keputusan, satu tempat)
