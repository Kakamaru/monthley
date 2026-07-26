# CASE-007 — Langganan bertindih dicaj dua kali

- **Tarikh ditemui:** 27 Julai 2026 (semasa kerja penyata ADR 0010)
- **Status:** BELUM DISIASAT. Bukti dikumpul, punca belum disahkan.
- **Skop:** enjin bil, bukan penyata

## Bagaimana ia ditemui

Penyata M01 memaparkan INV000032 (RM100) dengan DUA sub-baris identik:
"PRKING KERETA kedua · Julai 2026", RM50 setiap satu. Pada mulanya
disyaki pepijat paparan; ia bukan.

## Fakta

Akaun 260 mempunyai DUA langganan bagi produk 197 yang sama:

| id | start_date | end_date | dicipta |
|---|---|---|---|
| 250 | 2026-07-19 | NULL | 19 Julai 14:41 |
| 326 | 2026-07-01 | 2026-08-15 | 20 Julai 19:10 |

Tempohnya BERTINDIH. INV000032 dijana 23 Julai 23:05 dan mengecaj
KEDUA-DUANYA dalam larian yang SAMA — bukan dua larian berasingan.

Kedua-dua baris RM50 PENUH. Tiada prorata dikenakan, walaupun langganan
250 bermula 19 Julai dan `financial_document_line.proration_ratio`
wujud khusus untuk kes ini.

## Kenapa perlindungan sedia ada tidak menangkapnya

**`uk_subscr (account_id, product_id, start_date)`** — kunci ini
membenarkan produk sama pada akaun sama asalkan `start_date` berbeza.
Ia menghalang langganan yang IDENTIK, bukan yang BERTINDIH.

**`idem_key` = `akaun:produk:period_start`** — menghasilkan
`260:197:2026-07-01` dan `260:197:2026-07-19`. Dua kunci berbeza, jadi
`uk_line_idem` membenarkan kedua-duanya. Perlindungan itu menghalang
penjanaan BERULANG bagi langganan yang sama; ia tidak pernah direka
untuk menangkap tindihan.

Kedua-dua perlindungan berfungsi seperti ditulis. Takrifannya yang
mengandaikan satu produk hanya boleh mempunyai satu tempoh aktif per
akaun pada satu masa.

## Soalan yang belum dijawab

**1. Apa peraturan perniagaannya?** Bolehkah seorang pelanggan menyewa
DUA petak parking (produk sama, dua kontrak)? Jika ya, INV000032 betul
dan hanya keterangan perlu boleh dibezakan. Jika tidak, tindihan mesti
dihalang di peringkat langganan.

Jangkaan pemilik produk semasa perbincangan: "satu produk, satu akaun,
satu bulan = satu caj". Jika itu muktamad, `uk_subscr` dan `idem_key`
kedua-duanya terlalu longgar.

**2. Kenapa tiada prorata?** Langganan bermula 19 Julai sepatutnya
dicaj sebahagian bulan, bukan RM50 penuh.

**3. Bagaimana enjin memilih langganan?** `InvoiceGenerationService`
tidak merujuk `AccountSubscription` secara terus; laluannya belum
dikesan.

## Skala

Satu kes sahaja dalam `monthley_new` setakat ini. TIADA penjanaan
berlaku untuk akaun/produk itu selepas 23 Julai 23:05, jadi data tidak
boleh mengesahkan sama ada perubahan kemudian membetulkannya. Commit
selepas itu (`2f1e649`) berkenaan `startDate` null, bukan tindihan.

## Kenapa ia tidak diselesaikan serta-merta

Ini menyentuh enjin bil — kod yang MENGECAJ duit pelanggan, bukan yang
memaparkannya. Peraturan perniagaan belum jelas, dan jawapannya
menentukan sama ada dua kekangan pangkalan data perlu diketatkan.
Itu memerlukan ADR dengan ujian, bukan patch.

## Dibetulkan buat masa ini (paparan sahaja)

`StatementFormatter.period()` kini menunjukkan tarikh untuk sebahagian
bulan: "Julai 2026" lawan "19-31 Julai 2026". Dua baris sah kini boleh
dibezakan. Ini TIDAK menyelesaikan CASE-007; ia hanya menghentikan
penyata daripada menjadikan dua baris kelihatan seperti pendua.

## Rujukan
- 0010-penyata-akaun.md
- billing-rules.md
