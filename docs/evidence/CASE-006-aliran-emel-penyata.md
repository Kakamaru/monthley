# CASE-006 — Aliran e-mel dan pautan awam penyata (legacy)

- **Tarikh:** 26 Julai 2026
- **Sumber:** p302_my produksi + e-mel sebenar + halaman awam legacy
- **Tujuan:** memahami aliran "bayar tanpa log masuk" sebelum mereka
  bentuk semula; ia belum wujud dalam sistem baharu

## Aliran legacy

Setiap kali invois dijana, legacy mencipta TIGA benda:

1. Invois sebenar
2. Satu dokumen `P` (contoh `P000002688`) dalam `mon_sp_fi_doc`
3. Satu baris dalam `mon_notif_q` (gilir e-mel)

E-mel mengandungi PDF sebagai lampiran DAN pautan:

    https://monthley.com/app/stmt/{uuid}

UUID itu milik dokumen `P`, bukan akaun dan bukan baris notifikasi.
Pautan membuka penyata HTML dengan butang **Download** dan **Pay**.
Tiada log masuk. Halaman memaparkan penyata tahun semasa — sama seperti
yang SP lihat — dengan Login/Signup di header.

## Penemuan 1 — kunci berbeza setiap e-mel (legacy BETUL)

| akaun | bil P | uuid unik | julat |
|---|---|---|---|
| MY0000600003w | 51 | 51 | 2025-12 hingga 2026-05 |
| MY0000600001r | 45 | 45 | 2023-12 hingga 2026-05 |
| MY0000600002i | 41 | 41 | 2024-03 hingga 2026-05 |

`bil P = uuid unik` pada setiap akaun. Setiap e-mel membawa kunci
sendiri, jadi memajukan e-mel Januari tidak memberi akses kekal.
Hipotesis awal (UUID akaun diguna semula) SALAH.

## Penemuan 2 — dokumen hantu ialah kos jalan pintas

Dokumen `P` tidak menggerakkan baki, tiada baris ledger, tidak pernah
muncul sebagai transaksi. Ia cengkerang kosong yang wujud semata-mata
kerana jadual dokumen sudah mempunyai penjana UUID dan penomboran.

Kosnya: 51 rekod bukan-kewangan dalam jadual kewangan untuk SATU akaun
sejak 2023.

Dalam skema baharu ini lebih teruk: `account_document_entry` akan
memasukkannya melalui cabang `ELSE 0`, jadi ia muncul sebagai baris
kosong dalam penyata. Itulah hujah terkuat untuk jadual token
berasingan — bukan kekemasan, tetapi mengelak meletakkan
bukan-dokumen ke dalam jadual dokumen.

## Penemuan 3 — gilir ialah corak yang betul

`mon_notif_q` mempunyai `sts_code = 'P'` (pending) dan `sent_dt`.
Penjanaan invois TIDAK menghantar e-mel secara segerak: ia menulis
baris gilir. Jika SMTP mati, invois tetap terjana. Jika 3,000 akaun
dijana, tiada 3,000 panggilan SMTP dalam transaksi yang sama.

Lajur legacy yang berguna: `atch_gen` (lampiran perlu dijana),
`param1_key/val` dan `param2_key/val` (contoh `p_acc_no`,
`p_period = 2026230700`), `priority_`, `add_emails`, `type_code`.

`notification_queue` kita SUDAH wujud tetapi KOSONG (0 rekod), dan
skemanya lebih nipis: tiada `attachment`, tiada `account_id`, tiada
parameter. Ia perlu dikembangkan sebelum boleh membawa penyata.

## Penemuan 4 — "Sila Pilih" disahkan sekali lagi

`param2_val = 2026230700` ialah `period_id`, dan penyata yang terpapar
berbunyi "Year 2026" dengan Previous Amount RM430.00 — iaitu penyata
ikut tahun dengan baki bawa hadapan yang BETUL.

Jadi apabila `p_period` diberi, legacy menghasilkan penyata tahun yang
betul. "Sila Pilih" bukan sekatan enjin; ia parameter kosong yang
jatuh melalui `LIKE '%'` (CASE-004 / ADR 0010 L3). Ini pengesahan
daripada aliran sebenar, bukan daripada templat sahaja.

## Kesan pada ADR 0010

**Keputusan 1 — EMPAT pemanggil, bukan tiga.** Pautan e-mel awam
mempunyai sempadan kebenarannya sendiri: token dalam URL, bukan
`TenantContext` dan bukan `payer_user_id`.

**Keputusan 7 — TIGA penulis, bukan dua.** PDF (lampiran e-mel dan
muat turun), XLSX (tab Laporan), dan HTML (halaman awam). Ketiga-tiga
atas `StatementModel` yang sama. HTML paling mudah: templat Thymeleaf
sedia ada dirender ke respons dan bukan ke openhtmltopdf.

## Untuk dibina (ADR 0011)

1. Jadual `statement_access_token` — token rawak, `sp_code`,
   `account_id`, tahun, `expires_at`, `revoked_at`. Bukan UUID
   dokumen: kita tidak mahu dokumen hantu.
2. Laluan awam `/stmt/{token}` — `permitAll`. `sp_code` diambil
   daripada TOKEN, tidak pernah daripada URL atau parameter; jika
   tidak sesiapa boleh menukarnya.
3. `StatementHtmlWriter` — penulis ketiga.
4. Butang Pay: token membawa kepada aliran FPX sedia ada (ADR 0007).
   Pembayar tidak melihat akaun tanpa log masuk; resit dihantar
   melalui e-mel.
5. `EmailPort.sendStatement(...)` + pengembangan `notification_queue`
   (lampiran, `account_id`, parameter, percubaan semula).
6. Keputusan luput: e-mel Januari 2026 dibuka Mac 2027 — token
   menyimpan tahunnya, jadi ia sentiasa memaparkan tahun ia dijana.
   Jika tidak, pelanggan melihat penyata yang tiada kaitan dengan
   invois dalam e-mel itu.

## Rujukan
- 0010-penyata-akaun.md
- CASE-004-ledger-line-taxonomy.md
