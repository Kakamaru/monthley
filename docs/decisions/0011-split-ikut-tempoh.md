# ADR 0011 — Split invois ikut TEMPOH, bukan produk sahaja

- **Status:** Diterima (27 Julai 2026)
- **Meminda:** ADR 0008 keputusan 2
- **Berkait:** ADR 0010 (penyata), billing-rules.md §5 §6 §7

## Konteks

ADR 0008 menetapkan `split_invoice_by_product` memecah dokumen ikut
PRODUK. Itu memadai apabila satu larian menghasilkan satu tempoh.

Ia tidak memadai apabila satu larian menghasilkan BANYAK tempoh:

    account.charge_frequency  = YEAR      -> kitaran asas = tahun penuh
    product.charge_frequency  = MONTHLY   -> satu caj setiap bulan

Akaun M04 produksi menghasilkan SATU dokumen dengan dua belas baris
parking bulanan. Semuanya di bawah satu nombor invois.

**Mekanisme ini mudah disalahfaham**, dan penulis ADR ini
menyalahfahaminya sepanjang siasatan: disangka `start_date` NULL
menyebabkan enjin mengejar ke belakang. Bukan. `PeriodResolver`
mengembalikan satu kitaran asas; `account.charge_frequency` menentukan
PANJANG kitaran itu, dan `product.charge_frequency` menentukan berapa
kali produk dicaj DALAM kitaran tersebut. `start_date` hanya menetapkan
had bawah.

Ditulis di sini kerana orang seterusnya akan menyalahfahaminya juga.

## Masalah

Kadar berubah selepas AGM pertengahan tahun — perkara yang berlaku
setiap tahun dalam JMB. SP mesti membatalkan Ogos hingga Disember dan
menjana semula dengan kadar baharu, tanpa menyentuh Januari hingga
Julai yang mungkin sudah dibayar.

Dengan satu dokumen bagi dua belas bulan, itu mustahil. `cancelDocument`
membatalkan keseluruhan invois; tiada laluan untuk membatalkan sebahagian
baris (`financial_document_line.active` wujud tetapi tiada endpoint
menggunakannya — 0 daripada 106 baris pernah dinyahaktifkan).

Nota kredit bersasar dipertimbangkan dan ditolak: ia menambah dokumen,
dan walaupun `LineAllocationWriter` mengikatnya kepada baris invois
(jadi laporan kutipan-ikut-produk KEKAL tally, bertentangan dengan
kebimbangan awal), ia menggunakan FIFO — tempoh tertua diknock dahulu,
bukan tempoh yang SP mahu laraskan.

## Keputusan

**1. Kunci pengumpulan ialah (tempoh, produk).**

    split = 0  ->  SATU dokumen untuk seluruh larian
    split = 1  ->  SATU dokumen per PRODUK per TEMPOH

Legacy sudah berbuat demikian. Pandan Mewah 11/01/2020 09:24:47
menghasilkan EMPAT invois dalam satu cap masa (2 produk x 2 bulan);
SRIAH 26/01/2026 menghasilkan TIGA (3 produk x 1 bulan). Bukti ada
dalam PDF produksi sejak hari pertama siasatan ADR 0010.

**2. Dokumen membawa tempoh LIPUTANnya, bukan tempoh larian.**

ADR 0008 menetapkan tempoh larian kerana satu dokumen boleh merangkumi
beberapa tempoh. Setelah dipecah ikut tempoh, liputan dan dokumen ialah
perkara yang sama.

Tanpa ini, dua belas invois bulanan semuanya bertanda '2025' dan tidak
boleh dibezakan dalam senarai — M04 menunjukkan tepat itu
(`period_id = 2025000000` pada dokumen yang meliputi Januari sahaja).

**3. Kesan pada operasi biasa: TIADA.**

`invoice_gen_freq = MONTHLY` bermakna satu larian = satu tempoh, jadi
produk x tempoh = produk x 1. Perbezaan hanya muncul semasa penjanaan
merentas beberapa tempoh — akaun YEAR dengan produk MONTHLY, atau
mengejar tempoh terdahulu.

**4. `split = 0` menerima had pembatalan.**

SP yang mematikan split mendapat satu dokumen untuk seluruh larian, dan
membatalkan sebahagian bermakna membatalkan keseluruhannya. Itu
keputusan sedar, bukan terlepas pandang.

Ia MESTI dijelaskan pada skrin tetapan, bukan hanya semasa onboarding.
Onboarding ialah manusia bercakap dengan manusia sekali sahaja; setahun
kemudian orang yang mengambil alih tidak tahu perbualan itu pernah
berlaku. Peraturan yang hidup dalam ingatan seseorang bukan peraturan
(cara-kerja guard 6).

Cadangan teks tooltip:

    Split invois ikut produk
      Dihidupkan  -> satu invois per produk per tempoh. Boleh batal satu
                     bulan tanpa menyentuh yang lain.
      Dimatikan   -> satu invois untuk semua produk dan tempoh dalam satu
                     larian. Membatalkan sebahagian bermakna membatalkan
                     keseluruhan invois.

Lalai untuk SP baharu: HIDUP.

**5. Keterangan invois satu baris mesti membawa tempoh.**

Selepas split ikut tempoh, dua belas invois bulanan bagi produk yang
sama menghasilkan dua belas baris penyata yang identik ('PARKING
MOTOR'). Tiada sub-baris untuk membawa tempoh kerana invois satu baris
tidak dipecahkan (ADR 0010: sub-baris tunggal hanya mengulang dirinya).

`StatementService.keteranganFor` kini menambah tempoh: 'PARKING MOTOR ·
Januari 2025'.

## Ujian

`SplitByPeriodTest` — akaun YEAR + produk MONTHLY:

- `split = 1` menghasilkan lebih daripada satu dokumen, setiap satu
  dengan `period_id` UNIK dan satu baris
- `split = 0` menghasilkan satu dokumen dengan banyak baris
- **Membatalkan satu tempoh tidak menyentuh yang lain** — ujian ini
  menguji SEBAB perubahan wujud, bukan sekadar mekanismenya

Percubaan pertama menggunakan akaun MONTHLY dan gagal: satu dokumen
sahaja dijana. Andaian tentang punca adalah salah, dan ujian yang
mendedahkannya.

## Alternatif ditolak

- **Pembatalan peringkat baris** — `active` wujud dan `idem_key` sudah
  direka mengelilinginya, tetapi ia menjadikan `financial_document.amount`
  boleh-ubah selepas dokumen dikeluarkan. Dokumen kewangan yang jumlahnya
  berubah bukan lagi rekod tetap.
- **Nota kredit bersasar** — FIFO menyasarkan tempoh tertua, bukan tempoh
  yang dimaksudkan. Memerlukan pemilihan baris, iaitu ciri baharu.
- **Kekalkan split ikut produk sahaja** — masalah AGM kekal tidak
  boleh diselesaikan tanpa ciri baharu.

## Penemuan berasingan (belum dikerjakan)

Semasa menguji, checkbox pemilihan invois pada Manual Payment dihantar
ke backend tetapi DIABAIKAN: `PaymentService` menghormati
`targetDocumentIds` hanya apabila `service_provider.allow_selective = 1`,
dan SP0002 mempunyainya 0.

Kerani menandakan enam invois parking; sistem membayar invois insurance
yang TIDAK ditandakan. Tiada amaran.

Tiga kerja timbul:

1. UI mesti menyembunyikan checkbox apabila `allow_selective = 0`
2. `sp_document_setting.selective_payment` (V2) tiada siapa membacanya —
   dua lajur untuk satu konsep, guard 6
3. `allowSelective` menelan RuntimeException dan mengembalikan false —
   kegagalan query mematikan pemilihan secara senyap

Legacy mengikat pemilihan kepada tetapan split; Monthley memisahkannya
sebagai dua tetapan bebas atas permintaan pemilik produk.

## Rujukan
- 0008-split-invoice-and-gen-day.md (dipinda oleh dokumen ini)
- 0010-penyata-akaun.md
- domain/billing-rules.md §5 §6 §7
