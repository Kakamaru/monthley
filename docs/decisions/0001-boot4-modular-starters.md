# ADR 0001 — Spring Boot 4 memodularkan auto-configuration

- **Status:** Diterima
- **Tarikh:** 18 Julai 2026
- **Konteks:** Flyway tidak pernah berjalan sejak projek bermula

---

## Masalah

Flyway **tidak pernah** melakukan migrasi secara automatik. Bukan sekali.

Skema dibina dengan menjalankan setiap fail secara manual:

```bash
mysql -u monthley -pdevpass monthley_new < V17__*.sql
mysql -u monthley -pdevpass monthley_new -e "INSERT INTO flyway_schema_history ..."
```

Ini kelihatan normal kerana ia sentiasa berkesan. `flyway_schema_history` wujud (kita yang isi), skema betul (kita yang bina), aplikasi boot (table sudah ada).

Punca ditemui hanya selepas `DROP DATABASE` dan cuba boot dari kosong:

```
Table 'monthley_new.flyway_schema_history' doesn't exist
```

`spring.flyway.enabled: true`. `flyway-core` dan `flyway-mysql` dalam classpath. Fail migrasi dalam `target/classes/db/migration/`. Log tiada satu baris pun tentang Flyway.

---

## Sebab

Spring Boot 4 memecahkan jar `spring-boot-autoconfigure` (2 MB dalam Boot 3.5) kepada modul berfokus. `flyway-core` memberi enjin; auto-configuration duduk dalam artifact berasingan.

> Spring Boot 4.x tidak lagi auto-konfigur Flyway dengan `flyway-core` sahaja. Flyway tidak akan menjalankan migrasi automatik, walaupun properties Flyway ditakrifkan.

Rujukan:
- https://spring.io/blog/2025/10/28/modularizing-spring-boot/
- https://docs.spring.io/spring-boot/appendix/auto-configuration-classes/spring-boot-flyway.html

---

## Keputusan

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

`flyway-core` **dibuang** — starter membawanya. `flyway-mysql` kekal untuk sokongan dialek.

**Flyway memiliki skema. Tiada `mysql <` manual. Tiada `INSERT INTO flyway_schema_history`.**

---

## Akibat

### Empat bug terdedah serta-merta

Semuanya tersembunyi selagi Flyway tidak berjalan:

| Bug | Kesan kalau tidak dijumpai |
|---|---|
| `flyway-core` tanpa starter | **Deploy production mencipta DB kosong.** Sifar table. |
| `modulith.events.republish-outstanding-events-on-restart: true` | Modulith membaca `event_publication` semasa startup, sebelum Flyway menciptanya. Boot gagal atas DB kosong. Dimatikan — kita tidak guna event Modulith (tiada `@ApplicationModuleListener` / `publishEvent` dalam kod). |
| V13 `DROP INDEX uk_sp_membership` | Nama salah. Index sebenar `uk_membership`, dan sudah `(sp_code, user_id, role)` sejak V1. `ALTER` itu sisa. |
| V19 `ADD COLUMN period_id` | Sudah wujud sejak V1 (menunjuk `accounting_period` yang tidak pernah dipakai). Lihat `docs/schema/dead-columns-and-tables.md`. |

Tiga yang terakhir **tidak dapat dijumpai** selagi Flyway tidak berjalan. Langkah manual kita memintas validasi yang akan menangkapnya.

### Salah diagnosis yang perlu dibuang

| Yang kita percaya | Realiti |
|---|---|
| "`mvn flyway:migrate` kerap gagal (isu VPN/JDBC)" | Ia tidak pernah dikonfigur |
| "Flyway perlukan slate bersih — drop dan recreate DB bila bootstrap" | Ia perlukan starter |
| Ritual `mysql <` manual | Tampalan untuk masalah yang tidak nampak |

Nota `docs/` yang mengandungi perkara di atas telah dibetulkan.

### Kos yang telah dibayar

- V16 didaftar tiga kali dalam `flyway_schema_history` (INSERT manual tidak idempotent)
- V17 didaftar sebelum failnya wujud (heredoc gagal senyap, pendaftaran berjaya)
- V15 berganda dari sesi terdahulu
- Setiap satu perlu dibersihkan dengan tangan
- Skema dev menyimpang dari apa yang migrasi sebenarnya hasilkan — kita tidak tahu sehingga drop dan bina semula

---

## Pengajaran

**"Ia berkesan" bukan "ia betul."**

Ritual manual berkesan selama berbulan. Setiap sesi mengesahkan bahawa ia normal. Punca hanya terdedah bila kita cuba laluan yang production akan lalui: DB kosong, boot, biar sistem yang bekerja.

**Kalau anda membina tabiat untuk memintas alat, siasat kenapa alat itu tidak berfungsi.**

Ini kes khusus prinsip `cara-kerja.md` §2 — jangan teka, baca. Kita mendiagnosis "VPN/JDBC" tanpa membaca log. Log akan menunjukkan: Flyway tidak pernah bercakap.

### Corak yang sama mungkin ada di tempat lain

Boot 4 memodularkan **semua** auto-config. Mana-mana dependency mentah yang dulu auto-dikonfigur mungkin senyap sekarang. Kalau sesuatu "sepatutnya berfungsi" tetapi diam:

```bash
grep -i "<nama-teknologi>" backend.log
ls ~/.m2/repository/org/springframework/boot/ | grep -i "<nama-teknologi>"
```

Log kosong = auto-config tidak aktif = starter hilang.
