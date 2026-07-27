# CASE-007 — Langganan bertindih dicaj dua kali

- **Tarikh ditemui:** 27 Julai 2026 (semasa kerja penyata ADR 0010)
- **Status:** PUNCA DISAHKAN, GUARD DIPASANG (27 Julai 2026).
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

---

# PENYELESAIAN (27 Julai 2026)

## Prorata: BUKAN pepijat

Siasatan awal mendakwa "tiada prorata dikenakan walaupun proration_ratio
wujud". Itu SALAH — dakwaan dibuat tanpa membaca kod.

`InvoiceCalculator` baris 73:

    boolean canProrate = account.startDate() != null && product.prorated();

`account.start_date` pada akaun 260 ialah NULL, jadi prorata mati dengan
sengaja. Komen di atasnya menerangkan sebabnya, dan ia kukuh:

> Kosong -> sub.start_date hanya menentukan BILA. Kitaran berjalan dicaj
> PENUH. Sebab: tanpa isytihar SP, satu-satunya tarikh yang kita ada
> ialah bila kerani menaip. Memprorate berdasarkannya bermakna
> mengenakan caj berdasarkan KELAJUAN KEMASUKAN DATA.

Dirujuk dalam billing-rules.md §6. RM50 penuh untuk kedua-dua baris
adalah tingkah laku yang ditulis. Soalan prorata DITUTUP.

## Punca sebenar: peraturan hidup dalam UI sahaja

Peraturan perniagaan disahkan pemilik produk: **satu akaun, satu produk,
satu langganan**. Skrin "Add Subscription" hanya memaparkan produk yang
belum dilanggan.

Tetapi penapis itu wujud di FRONTEND SAHAJA. Backend menerima apa yang
dihantar: `AccountController` laluan edit mencipta `AccountSubscription`
apabila `line.id() == null`, tanpa menyemak produk sudah dilanggan.
Grep untuk penapis di backend mengembalikan kosong.

Akaun 260 mendapat dua langganan produk 197 melalui laluan itu.

Ini corak cara-kerja guard 6: peraturan yang hidup hanya dalam UI bukan
peraturan. DB tidak pernah dimaklumkan.

## Guard dipasang

`existsByAccountIdAndProductIdAndStatus` pada dua laluan cipta:

- **Edit** — produk dengan langganan ACTIVE sedia ada ditolak
- **Cipta** — permintaan sama membawa produk sama dua kali ditolak
  (DB belum ada baris untuk disemak)

ENDED tidak menyekat. Pelanggan boleh berhenti dan melanggan semula, dan
setiap kitaran mendapat barisnya sendiri supaya sejarah kekal — corak
sama seperti legacy (status D lawan A, add semula mencipta baris baharu
status A).

Tarikh TIDAK terlibat dalam guard. Tiga lapisan menjaga tiga perkara
berbeza:

| Lapisan | Menjaga |
|---|---|
| Guard langganan (ACTIVE) | satu produk, satu langganan hidup |
| effStart/effEnd + PeriodResolver | tempoh MANA yang dicaj |
| idem_key | tempoh itu dicaj sekali sahaja |

Disahkan oleh ujian manual pemilik produk: mengubah start_date/end_date
pada langganan SEDIA ADA tidak menjana pendua — sistem menolak dengan
"invois dah dijana".

`activeSubscriptions()` disahkan menapis `status = ACTIVE`, jadi
langganan ENDED tidak pernah dicaj.

## Keputusan tertunggak: idem_key

`idem_key` menggunakan `period_start`, bukan `period_id`. Dua langganan
menghasilkan `period_start` berbeza dalam `period_id` yang SAMA, jadi
kunci membenarkan kedua-duanya:

    260:197:2026-07-01
    260:197:2026-07-19   <- period_id sama (2026230700)

Menukarnya kepada `period_id` akan menjadikan DB menguatkuasakan
peraturan secara muktamad. TIDAK dilakukan buat masa ini kerana:

- Dengan guard di tempatnya, dua langganan produk sama tidak boleh wujud,
  jadi kedua-dua medan akan memberi hasil sama
- `period_start` mempunyai maksud untuk baris yang BUKAN daripada
  langganan (pelarasan, caj sekali); menukar kunci menyentuh laluan yang
  belum diperiksa
- V18 sudah mencabangkan kunci ini untuk ONE_TIME; menambah cabang
  ketiga memerlukan pemahaman penuh ketiga-tiganya

Direkod sebagai pertahanan mendalam yang belum diperlukan. Jika guard
pernah gagal, barulah ia berbaloi.

## Data sedia ada

Sub 250 dan 326 masih wujud; INV000032 masih mempunyai dua baris. Guard
hanya menghalang yang BAHARU. Pembersihan dilakukan semasa reset data
ujian seterusnya.
