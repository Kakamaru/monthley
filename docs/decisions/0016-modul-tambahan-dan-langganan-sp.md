# ADR 0016 — Modul tambahan, pelan SP, dan pemisahan hak daripada bil

- **Status**: Diterima
- **Tarikh**: 10 Ogos 2026
- **Menggantikan**: —
- **Berkaitan**: ADR 0009 (baki tunggal), ADR 0015 (gerbang bayaran, belum dibina)

## Konteks

Monthley ialah sistem bermodul. Yang siap setakat ini ialah **modul asas**:
akaun, produk, langganan, jana bil, bayaran manual, dokumen kewangan,
penyata, dan sepuluh laporan. Bayaran FPX termasuk dalam asas.

Modul tambahan yang dirancang: **Aduan, Perbelanjaan, Sumbangan, Memo**, dan
kemudian modul ikut sektor (sekolah: pengurusan pelajar, pengurusan guru).
Setiap satu dikenakan caj tambahan.

Tiga soalan perlu dijawab:

1. Modul dibungkus dalam pelan, atau add-on berasingan?
2. Bagaimana SP dibil — enjin bil baharu, atau guna Monthley sendiri?
3. Bagaimana hak akses ditentukan dan dikuatkuasakan?

Sistem lama (`p302_my`) sudah menjawab soalan kedua tanpa disedari:
Rapidevelop ialah SP, setiap SP pelanggan ialah **akaun** di bawahnya, dan
modul tambahan sudah pun wujud sebagai **produk** (`EXPN — Expenses —
RM5.00 — Monthly charge`), bersama `MON-1000` (pelan), `DM` (migrasi data),
`TRAIN2` (onboarding). Tetapi sistem lama tiada portal superadmin, jadi
soalan ketiga tidak pernah timbul.

## Keputusan

### 1. Modul ialah add-on berasingan, bukan sebahagian pelan

Pelan = **kuota akaun** sahaja. Modul ditambah satu-satu.

Ditolak: modul dibungkus dalam pelan (SP kecil yang cuma perlukan Aduan
terpaksa naik pelan penuh, dan Aduan tidak boleh dijual bersendirian), dan
"kedua-dua" (dua sumber kebenaran untuk soalan "SP ini ada Aduan?" — bila
SP ada Aduan melalui pelan *dan* melalui add-on, "bila tamat?" jadi soalan
bermusuh).

### 2. SP dibil melalui Monthley sendiri

Rapidevelop ialah SP. Setiap SP pelanggan ialah **akaun** di bawah
Rapidevelop. Pelan dan modul ialah **produk** bawah Rapidevelop.
Langganan SP ialah baris `account_subscription` biasa.

Akibatnya enjin bil sedia ada terus berfungsi untuk bil SP: jana bil,
resit, tunggakan, penyata, penalti lewat, auto-knock advance. **Sifar kod
bil baharu.** Dan kami jadi pengguna pertama produk sendiri — pepijat kena
pada kami dahulu sebelum kena pada pelanggan.

Satu akaun SP boleh ada banyak baris langganan serentak:

| Produk | Kod | Frekuensi |
|---|---|---|
| Migrasi Data | `DM` | ONE_TIME |
| Onboarding & Latihan | `TRAIN2` | ONE_TIME |
| Pakej 300 | `PLAN-300` | MONTHLY |
| Modul Aduan | `MOD-ADUAN` | MONTHLY |

Enjin bil tidak perlu tahu mana "pelan" dan mana "modul" — ia hanya menjana
invois daripada langganan aktif.

### 3. Harga hidup di `product` sahaja

`service_plan` menyimpan `product_id`; lajur `price_monthly` dan
`price_yearly` **digugurkan**.

Sebab: harga di dua tempat akan menyimpang. Skrin superadmin memaparkan
"Pakej 300 — RM300.00/bln" daripada `service_plan`, sedangkan invois keluar
daripada `product.unit_rate`. Ubah satu, lupa satu, dan SP menerima invois
yang tidak sepadan dengan apa yang dia lihat semasa mendaftar.

Ini corak M04 yang sama (ADR 0009): satu nilai, satu tempat. Guard 6.

`product` sudah mempunyai segalanya yang diperlukan: `charge_frequency`,
`unit_rate`, `income_gl_account_id`, `prorated`, `late_penalty`.
`service_plan` hanya perlu tahu kuota akaun dan produk yang mewakilinya.

### 4. Hak dipisahkan daripada bil

Dua soalan berbeza, dua jadual:

| Soalan | Dijawab oleh |
|---|---|
| "SP ini kena bayar apa?" | `account_subscription` bawah Rapidevelop |
| "SP ini boleh guna Aduan?" | `sp_module` |

Ini **bukan** pelanggaran Guard 6 kerana soalannya berlainan. Kalau hak
diderive terus daripada langganan, dua masalah timbul: modul Aduan terpaksa
membaca data SP Rapidevelop (merentas tenant, melanggar `TenantFilter`),
dan SP lambat bayar kehilangan akses serta-merta tanpa tempoh budi.

**Tetapi** kedua-duanya mesti sentiasa berubah bersama. Semua perubahan
melalui satu servis (`ModuleEntitlementService`) yang mengendalikan
pasangan itu sekali gus. Tidak pernah diubah berasingan — kalau tidak, SP
boleh dibil untuk modul yang tidak boleh diguna, atau menggunakan modul
secara percuma selama berbulan.

### 5. Satu aliran permohonan untuk semua perubahan

`sp_change_request` — bukan `sp_module_request`. Naik pelan, turun pelan,
tambah modul, henti modul: semua melalui jadual dan skrin yang sama.

SP mohon → superadmin nilai (di luar sistem) → lulus atau tolak.
Penolakan **wajib** ada `decision_note` yang SP nampak.

Jadual berasingan bagi setiap jenis perubahan bermakna tiga skrin peti
masuk superadmin yang melakukan kerja yang sama.

### 6. Tarikh: hak serta-merta, bil pada 1hb

| Peristiwa | Hak | Bil |
|---|---|---|
| Modul diluluskan 15 Ogos | Aktif 15 Ogos | Bermula 1 September |
| Henti diluluskan 15 Ogos | Tamat 31 Ogos | Tiada caj September |
| Mohon 28 Julai, lulus 3 Ogos | Aktif 3 Ogos | Bermula 1 September |

Tiada prorata. Satu peraturan, dua arah, dan simetri: mohon pertengahan
bulan → guna terus, caj 1hb; henti pertengahan bulan → guna sampai hujung
bulan, tiada caj 1hb.

Bil dikira daripada **tarikh kelulusan**, bukan tarikh permohonan — kerana
tarikh kelulusan yang SP nampak dan boleh sahkan. Menggunakan tarikh
permohonan bermakna SP menerima invois untuk tempoh sebelum dia ada akses.

Inilah sebab hak dan bil perlu rekod berasingan: satu peristiwa, dua
tarikh berbeza. Satu rekod sahaja memaksa kita memilih satu, dan salah
satunya akan salah.

### 7. Kuatkuasa di backend, tiada pintasan superadmin

`ModuleGuard.require("ADUAN")` di setiap endpoint modul, corak sama dengan
`Access.requireRole()`.

Berbeza daripada `Access`: **superadmin TIDAK melepasi `ModuleGuard`**.
`Access.hasRole()` memulangkan `true` untuk superadmin kerana peranan ialah
tentang *pengguna*. Hak modul ialah tentang *SP*. Superadmin yang mencipta
data Aduan untuk SP yang tidak melanggan menghasilkan data yatim: wujud
dalam DB, tetapi tiada siapa boleh melihatnya.

Superadmin **meluluskan** modul; superadmin tidak **menggunakan** modul
bagi pihak SP.

**Skop: endpoint TULIS sahaja.** Endpoint baca dibenarkan dan memulangkan
keadaan kosong dengan penanda "belum dilanggan".

Ini mengesahkan semula keputusan 30 Julai (soalan 28): *benarkan masuk,
sekat transaksi*. SP boleh membuka skrin dan melihat apa yang ditawarkan;
tindakan yang mengubah data ditolak. Menyembunyikan menu sepenuhnya ialah
jualan yang hilang, dan menu yang lenyap secara misteri bila pakej berubah.
Manual Payment ialah rujukan corak: amaran + butang terkunci, backend tetap
menguatkuasakan.

Sekatan UI sahaja tidak memadai — sesiapa yang tahu URL API boleh mencipta
data tanpa bayar, dan kebocoran itu hanya disedari selepas ada 50 SP.

Soalan 28 menamakan jadual itu `sp_module_access`; ADR ini menggunakan
`sp_module`. Nama berbeza, konsep sama.

### 8. Kod modul ialah pemalar, bukan data semata

Kod (`ADUAN`, `PERBELANJAAN`) wujud sebagai pemalar dalam kod **dan** baris
`ref_module`. Jadual sahaja bermakna salah eja dalam
`ModuleGuard.require("ADAUN")` menjadi pepijat senyap yang membenarkan
semua orang masuk.

### 9. Modul tamat: data kekal, baca-sahaja

Data tidak dipadam. Tidak boleh cipta rekod baharu; rekod sedia ada boleh
dibaca. Memadam data aduan pelanggan kerana langganan tamat ialah masalah
undang-undang, bukan masalah teknikal.

### 10. Katalog ditapis ikut jenis perniagaan

`ref_module.business_types` (kosong = semua sektor). "Pengurusan Pelajar"
tidak sepatutnya muncul untuk SP jenis JMB — bukan kerana dilarang, tetapi
kerana ia mengarut, dan katalog yang penuh benda tidak berkaitan
merosakkan tujuan skrin itu.

### 11. Deaktivasi manual, bukan automatik

SP tertunggak tidak kehilangan akses secara automatik. Superadmin yang
memutuskan. "Berapa lama tertunggak baru dipotong" ialah keputusan
perniagaan yang lebih baik dibuat selepas ada pelanggan sebenar.

### 12. Onboarding: satu transaksi, tiga rekod

Superadmin mendaftar SP → dalam **satu transaksi**:

1. Baris `service_provider` (identiti tenant)
2. Baris `account` bawah Rapidevelop (pihak yang dibil)
3. Baris `account_subscription` untuk produk pelan (bil bermula)

`service_provider.billing_account_id` menyimpan pautan. Superadmin tidak
pernah menaip akaun secara manual.

SP platform ialah **`SP0000`** dengan bendera `is_platform_owner`. Bendera
itu wujud supaya kod tidak pernah menyebut kod SP tertentu: onboarding,
kelulusan modul, dan katalog produk semuanya perlu tahu "SP mana yang
membilkan SP lain", dan tiga tempat hardcode `"SP0000"` ialah persis corak
yang Guard 6 wujud untuk menghalang.

Kalau SP tercipta tetapi akaun gagal, kita mendapat SP yang tidak boleh
dibil — dan tiada siapa perasan sehingga hujung bulan.

## Skema

```sql
-- Katalog modul (rujukan platform, bukan per-SP)
ref_module (
  code            VARCHAR(30) PK,     -- ADUAN, PERBELANJAAN, MEMO, SUMBANGAN
  name            VARCHAR(100),
  description     VARCHAR(1000),      -- untuk skrin jualan SP
  video_url       VARCHAR(500),       -- penerangan modul
  product_code    VARCHAR(50),        -- produk bawah SP Rapidevelop; harga dari sini
  business_types  VARCHAR(200),       -- 'EDU,CLHO' — kosong bermakna semua
  sort_order      INT,
  status          ENUM('ACTIVE','INACTIVE')
)

-- Hak: SP ini boleh guna modul ini?
sp_module (
  id, sp_code, module_code,
  status      ENUM('ACTIVE','ENDED'),
  start_date  DATE,                   -- tarikh kelulusan (serta-merta)
  end_date    DATE NULL,              -- hujung bulan bila dihentikan
  UNIQUE (sp_code, module_code, status='ACTIVE')  -- via indeks separa
)

-- Permohonan: semua jenis perubahan
sp_change_request (
  id, sp_code,
  request_type  ENUM('MODULE_ADD','MODULE_END','PLAN_CHANGE'),
  module_code   VARCHAR(30) NULL,     -- untuk MODULE_*
  plan_id       BIGINT NULL,          -- untuk PLAN_CHANGE
  status        ENUM('PENDING','APPROVED','REJECTED'),
  requested_by, requested_at,
  decided_by, decided_at,
  decision_note VARCHAR(1000)         -- wajib bila REJECTED
)

-- Pindaan pada jadual sedia ada
service_plan       + product_id;  - price_monthly, - price_yearly
service_provider   + billing_account_id
```

## Akibat

**Baik:**
- Enjin bil sedia ada digunakan sepenuhnya; sifar kod bil baharu
- Dogfooding: pepijat kena pada kami dahulu
- Modul baharu (sektor sekolah) hanya perlu baris `ref_module` + `ModuleGuard`
- Satu aliran permohonan; satu skrin peti masuk superadmin
- Harga satu tempat sahaja

**Kos:**
- Rapidevelop mesti wujud sebagai SP sebelum SP lain boleh didaftar
  (masalah telur-dan-ayam; perlu seed)
- SP0001 (JMB Serai Wangi) didaftar sebelum mekanisme ini wujud — tiada
  akaun bil. Akaunnya perlu dicipta secara manual sekali, jika tidak
  laporan tunggakan Rapidevelop memberikan gambaran tidak lengkap
- Setiap endpoint modul memerlukan `ModuleGuard` — terlupa satu bermakna
  kebocoran senyap
- Dua rekod (hak + langganan) mesti sentiasa berubah bersama

## Sengaja TIDAK dibuat

- **Bayaran automatik** — menunggu ADR 0015. Kelulusan superadmin manual
  ialah mekanisme sementara yang boleh disisipkan bayaran kemudian tanpa
  mengubah mekanisme hak
- **Prorata** — sengaja tiada; caj bermula 1hb
- **Deaktivasi automatik bila tertunggak**
- **Percubaan percuma** — mekanisme hak menyokongnya (hak tanpa langganan),
  tetapi tiada aliran dibina
