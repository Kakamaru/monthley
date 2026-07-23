# Cara Kerja — Kama × Claude

> Baca dokumen ini dahulu bila membuka chat baru.
> Ia menerangkan **bagaimana** kita bekerja, bukan **apa** yang kita bina.
> Untuk peraturan domain, lihat `docs/domain/billing-rules.md`.

---

## 1. Konteks

**Kama** — pembangun/pemilik Monthley, SaaS bil berulang multi-tenant Malaysia oleh Rapidevelop Technology Sdn Bhd. Sistem lama (`p302_my`) masih hidup dan melayan ~71 SP. Projek semasa ialah **penulisan semula greenfield** — bukan migrasi berperingkat.

**Claude** — pasangan pair-programming. Menulis kod, menyoal reka bentuk, mencabar andaian.

**Bahasa** — Manglish santai. Panggilan "bosku" dua hala.

**Stack** — Spring Boot 4.1, Spring Modulith 2.1, Java 21, Maven, Angular 22, MySQL 9 (dev) / MariaDB 11 (prod), Flyway.

---

## 2. Prinsip teras: jangan teka, baca

Ini pengajaran termahal setakat ini.

**Kes 17 Julai 2026** — bug "email lupa kata laluan tak sampai". Claude membuat **empat teori berturut-turut**, semuanya salah:

1. Mail service belum wujud → salah, `ResendEmailService` ada
2. `MONTHLEY_RESEND_KEY` tak masuk proses Java → salah, `ps eww` tunjuk ia ada
3. Domain `perantau.org.my` tak verified di Resend → salah, email semalam sampai inbox
4. `VerificationService` ada guard `email_verified_at` → salah, tiada guard

Punca sebenar: `sp-header.interceptor.ts` menampal header `X-SP-Id` pada `/api/v1/auth/**`, dan `TenantFilter` menolak kerana `SecurityContext` kosong. **403.**

Ia dijumpai dalam **tiga saat** oleh satu tengokan DevTools Network. Claude telah meminta lima kali; setiap kali perbualan melompat ke teori lain.

### Kes kedua: Flyway yang tidak pernah berjalan

Kes email di atas berlangsung sejam. Kes ini berlangsung **berbulan**.

Skema dibina dengan menjalankan setiap migrasi dengan tangan:

```bash
mysql -u monthley -pdevpass monthley_new < V17__*.sql
mysql -u monthley -pdevpass monthley_new -e "INSERT INTO flyway_schema_history ..."
```

Ritual ini didokumenkan dalam fail ini sendiri, sebagai "cara kerja". Ia
sentiasa berkesan. `flyway_schema_history` wujud, skema betul, app boot.

Diagnosis kita: *"`mvn flyway:migrate` kerap gagal — isu VPN/JDBC."*

**Tiada siapa pernah membaca log.**

Bila akhirnya dibaca:

```bash
grep -i "flyway" backend.log
# (kosong)
```

Flyway tidak pernah bercakap. Ia tidak pernah dipanggil. `spring-boot-starter-flyway`
hilang dari `pom.xml` — Spring Boot 4 memodularkan auto-configuration, dan
`flyway-core` sahaja tidak lagi mencukupi.

Kos ritual itu:

- **Deploy production akan mencipta DB kosong.** Sifar table.
- Tiga bug migrasi lain terkumpul tanpa dikesan — V13 DROP INDEX dengan nama salah, V19 ADD COLUMN yang sudah wujud, Modulith membaca table sebelum Flyway menciptanya. Semuanya tersembunyi kerana langkah manual memintas validasi yang akan menangkapnya.
- Skema dev menyimpang dari apa yang migrasi sebenarnya hasilkan. Kita tidak tahu sehingga `DROP DATABASE` dan bina semula.
- V16 didaftar tiga kali, V17 didaftar sebelum failnya wujud.

Rujuk `docs/decisions/0001-boot4-modular-starters.md`.

### Dua pengajaran

**"Ia berkesan" bukan "ia betul."** Ritual manual berkesan setiap kali. Setiap
sesi mengesahkan bahawa ia normal. Punca hanya terdedah bila kita cuba laluan
yang production akan lalui.

**Kalau anda membina tabiat untuk memintas alat, siasat kenapa alat itu senyap.**
Jangan dokumenkan tampalan sebagai amalan. Itu menjadikan bug sebagai budaya.

### Peraturan yang terhasil

| Situasi | Buat ini |
|---|---|
| Bug dalam kod | Baca fail sebenar (`cat`, `grep`), jangan ingat |
| Bug dalam request | DevTools Network — status code dahulu |
| Soalan reka bentuk | Query production, jangan teori |
| Skema | `SHOW CREATE TABLE`, jangan andai |
| Reka bentuk UI | Baca `Monthley Portal.dc.html`, jangan reka |
| Alat berkelakuan pelik | Baca lognya. Log kosong = alat tidak aktif |

**Claude tidak sepatutnya menulis kod atas andaian.** Kalau maklumat tiada, minta — dan jangan teruskan sehingga dapat.

**Kama tidak sepatutnya melompat teori.** Kalau Claude minta satu benda spesifik, bagi benda itu dahulu sebelum bergerak.

---

## 3. Corak yang berkesan

### Interogasi production dengan SQL

Kaedah paling berkuasa setakat ini. Kama menjalankan query di DBeaver (VPN) untuk mengesahkan reka bentuk sebelum kod ditulis.

**Contoh:** Claude yakin `account.charge_frequency` menentukan aras invois. Satu crosstab menunjukkan silang penuh MO/QR/HF/YR — teori mati serta-merta, dan model empat-paksi yang betul terbongkar.

Data mengalahkan pendapat. Setiap kali.

### Ujian production langsung

Kama mencipta akaun ujian sebenar, menjana bil, kemudian menunjukkan baris yang terhasil.

**Lebih baik daripada query** — ia mendedahkan perkara yang tidak difikirkan untuk ditanya. Ujian split invois membongkar `last_charge`, `price_unit` override, dan dua aras `fi_period` sekaligus.

**Amaran:** production **hidup**. Data ujian meninggalkan dokumen sebenar dengan nombor sebenar dalam ledger SP sebenar. Rekod apa yang dicipta; contra `J01` kalau perlu.

### Paste data sebagai teks, bukan screenshot

Screenshot boleh dilihat tetapi nilainya sukar dibaca dengan yakin. **Teks mentah jauh lebih baik** — Claude boleh kira, bandingkan, sahkan formula.

Bila Kama paste 13 baris `mon_fi_doc_txn` sebagai teks, corak `fi_period` terus jelas. Screenshot baris yang sama mengambil tiga pusingan.

Screenshot baik untuk: UI, borang, susun atur.
Teks lebih baik untuk: data, DDL, hasil query.

### Patch bersasar, bukan tulis ganti

**Kes:** Claude pernah menulis semula `application.yml` sepenuhnya dan memadam tetapan yang sudah dibetulkan (`ddl-auto: none`, Envers dimatikan).

**Peraturan:** guna Python `str.replace()` dengan `assert` pada kiraan padanan:

```python
old = "..."
new = "..."
assert s.count(old) == 1, f"padanan: {s.count(old)}"
s = s.replace(old, new)
```

`assert` menghalang penulisan kalau padanan bukan tepat 1. Fail tidak disentuh kalau ragu.

**Jangan sekali-kali** tulis ganti fail konfigurasi sepenuhnya.

### Cabar, jangan ampu

Claude sepatutnya menyatakan bila ia tidak setuju, dan mengaku bila ia salah.

**Kes `fi_period`:** Claude mencadangkan `varchar(7)` 'YYYY-MM', hujah bahawa `fi_period` ialah state yang boleh drift. Kama mempertahankan corak partner-nya. Data menunjukkan 6,204 invois pada aras QR/HF/YR — `YearMonth` tidak mencukupi. **Claude salah, mengaku, teruskan.**

Penyelesaian akhir lebih baik daripada kedua-dua kedudukan asal: `period_id` disimpan (corak partner dihormati), `PeriodIds` fungsi tulen (prinsip stateless dikekalkan), `fi_period` rujukan sahaja.

Pertengkaran yang jujur menghasilkan reka bentuk yang lebih baik. Persetujuan pantas tidak.

---

## 4. Corak yang gagal

### Blok arahan berbilang dipaste serentak

`INSERT INTO flyway_schema_history` dijalankan **tiga kali** kerana blok terpaste berulang. V15 pun berganda dari sesi sebelumnya.

**Peraturan:** satu blok, satu masa. Sahkan output sebelum blok seterusnya.

### Daftar sebelum sahkan

Claude memberi arahan: cipta fail V17 → jalankan → daftar dengan Flyway. Heredoc gagal senyap, fail tidak wujud, tetapi pendaftaran Flyway **berjaya**. Flyway kemudian percaya V17 dipakai sedangkan tidak.

**Peraturan:** `ls -l` fail **dahulu**. Jalankan. Sahkan kesan (`DESCRIBE`). **Barulah** daftar.

### Heredoc dalam zsh

Gagal beberapa kali hari ini. Guna Python untuk penulisan fail:

```bash
python3 - << 'PYEOF'
open('path/to/file', 'w').write("""...""")
print("ditulis")
PYEOF
```

### zsh gotchas

| Simptom | Punca |
|---|---|
| `zsh: command not found: #` | `setopt interactivecomments` tidak aktif |
| `zsh: no matches found: tak?` | `?` ialah glob |
| `zsh: no matches found: COUNT(*)` | `*` ialah glob — quote argumen |
| `zsh: command not found: SELECT` | SQL dipaste ke shell, bukan `mysql>` |
| `cursh>` / `dquote>` | Quote tak tertutup — Ctrl+C |

### `ls | tail` untuk migrasi

`ls` susun ikut abjad. `V10`–`V15` jatuh **sebelum** `V5`. Guna `sort -V`:

```bash
ls -1 src/main/resources/db/migration/ | sort -V | tail -3
```

---

## 4b. Menghalang bug "campur konsep, compile lulus, ujian terlepas"

Tiga bug ditulis dalam satu sesi (18 Julai 2026) berkongsi satu corak:
kod untuk laluan yang tiada ujian menyentuhnya. Compile hanya semak jenis;
ujian hanya semak laluan yang datanya cetuskan.

| Bug | Compile lulus sebab | Ujian terlepas sebab |
|---|---|---|
| d.period selepas V19 | SQL native — Java tak semak | Query tak dipanggil dalam ujian itu |
| income_gl id-sebagai-kod | String.valueOf(Long) sah | Produk ujian income_gl = NULL |
| anchor_month TINYINT vs Integer | Kedua jenis wujud | Tiada ujian semak jenis lajur |

Empat penjaga, dari paling murah:

### 1. ddl-auto: validate (AKTIF)

Hibernate semak setiap entity lawan skema semasa boot. Penyimpangan -> boot
gagal, bukan senyap sampai ujian. Menangkap generation_day, d.period,
anchor_month serta-merta. Jangan tukar balik ke none.

### 2. mvn test SEBELUM commit, bukan selepas

Hari itu kita commit dulu, uji kemudian. Terbalik. Bug terkumpul dan kita
tak tahu patch mana yang pecahkan.

Peraturan: tiada commit tanpa mvn test hijau dahulu.

### 3. Ujian mesti sentuh laluan bukan-default

income_gl bug terlepas kerana semua produk ujian income_gl = NULL — cabang
bukan-null tak wujud dari sudut ujian.

Peraturan: bila menambah cabang (if null -> A, else -> B), ujian mesti hantar
KEDUA-DUA nilai. Kalau data ujian hanya cetuskan satu dahang, dahang satu
lagi tidak diuji.

### 4. Claude isytihar laluan tidak teruji

Ini yang paling penting dan paling rapuh — ia bergantung pada Claude ingat.

Bila Claude menulis cabang yang ujian sedia ada tidak sentuh, Claude MESTI
kata terus, dalam mesej yang sama:

> "Baris ini kredit ikut income_gl_account_id. Ujian sedia ada semua NULL —
>  laluan bukan-null TIDAK diuji. Perlu ujian dengan GL sebenar sebelum
>  percaya ini betul."

Pada 18 Julai, Claude TIDAK berbuat begini. Claude hantar kod dan berkata
"ini betulkan chart of accounts" — sedangkan ia sebenarnya memecahkannya,
dan tiada ujian yang akan menangkapnya. Kama yang segar menemuinya.

Penjaga 1-3 struktur; penjaga 4 kejujuran. Keduanya perlu.

### 5. Dokumen status dikemas kini dalam commit yang SAMA

Ditambah 23 Julai 2026 selepas tiga kes dokumen lapuk dalam satu sesi.

| Apa | Betul masa ditulis | Jadi salah bila |
|---|---|---|
| README "V1-V20 dipakai" | Ya | 10 migration kemudian |
| README "BillingContext MENYEKAT tab exclude" | Ya | V22 menyelesaikannya |
| ADR 0006 "tidak dibetulkan sekarang" | Ya | **satu commit** kemudian (P4.5) |

Kes ketiga paling merbahaya: jaraknya sejam. Kalau nota boleh jadi lapuk
dalam masa sejam, apa lagi yang ditulis berminggu lepas.

**Bahayanya bukan sekadar mengelirukan.** Dokumen lapuk menjemput kau
"betulkan" benda yang sudah betul — iaitu menyentuh kod yang stabil dan
teruji. Setiap sentuhan itu peluang memecahkan sesuatu yang berfungsi.
Kerja jadi dua tiga kali, dan risiko naik setiap kali.

Puncanya: kita catat keputusan, tapi tidak catat bila keputusan itu
BERUBAH. Menulis mudah; mengemas kini terlupa.

Peraturan: bila commit menukar status sesuatu yang bertulis — ADR
(ditangguh -> selesai, dicadang -> diterima), item README (belum hidup ->
siap), atau mana-mana ayat yang jadi tidak benar — kemas kini dalam
**commit yang sama**. Bukan commit berasingan. Bukan nanti.

Pemicu yang boleh diperiksa, sebelum setiap commit:

> "Adakah commit ini menjadikan mana-mana ayat bertulis tidak benar?"

Soalan itu boleh dijawab. "Ingat kemas kini dokumen" tidak — semua orang
setuju dengannya dan semua orang lupa.

Nota: mesej commit LAMA tidak diusik walaupun jadi lapuk — ia rekod sejarah
yang tepat pada masanya. Yang dikemas kini ialah dokumen yang dibaca sebagai
**keadaan semasa**: README dan ADR.

## 5. Persekitaran

### Backend

```bash
cd ~/MONTHLEY/monthley-backend
./mb restart        # skrip lifecycle (bukan dalam PATH — perlu ./)
```

`mvn spring-boot:run` **tanpa profil akan gagal** — datasource ditakrif bawah `on-profile: dev`. Guna `./mb` atau `-Dspring-boot.run.profiles=dev`.

`JAVA_HOME` mesti Java 21 (dalam `~/.zshrc`).

### Frontend

```bash
cd ~/MONTHLEY/monthley-frontend
ng serve            # proxy.conf.json → localhost:8080
```

Proxy dibaca **masa start sahaja** — tiada hot-reload.

### DB

```bash
mysql -u monthley -pdevpass monthley_new
mysql -u monthley --table -pdevpass monthley_new -e "DESCRIBE table_name;"
```

`--table` memberi output berformat. `SHOW CREATE TABLE ... \G` tidak berfungsi dengan `-e`.

### Flyway

**Flyway memiliki skema. Jangan jalankan migrasi dengan tangan.**

```bash
./mb restart      # Flyway migrate berlaku semasa startup
grep -i "flyway" backend.log
```

Migrasi gagal -> app tidak boot. Itu betul. Baca error, betulkan fail, restart.

Migrasi mesti berjalan atas **MySQL 9 (dev) dan MariaDB 11 (prod)**. Elak CTE
rekursif — guna jadual sementara.

Kalau satu migrasi gagal, `flyway_schema_history` menyimpan barisnya dengan
`success = 0` dan Flyway enggan teruskan. Buang baris itu selepas membetulkan
failnya:

```bash
mysql -u monthley -pdevpass monthley_new -e "DELETE FROM flyway_schema_history WHERE success = 0;"
```

Kalau skema dev menyimpang teruk, bina semula:

```bash
mysql -u root -e "DROP DATABASE monthley_new; CREATE DATABASE monthley_new;
GRANT ALL ON monthley_new.* TO 'monthley'@'localhost'; FLUSH PRIVILEGES;"
./mb restart
```

Ini menguji laluan yang production akan lalui. Buat sesekali.

#### JANGAN buat ini

Sehingga 18 Julai 2026, dokumen ini mengesyorkan `mysql < V17__*.sql` diikuti
`INSERT INTO flyway_schema_history`. **Itu salah.** Flyway tidak pernah berjalan
kerana `spring-boot-starter-flyway` hilang — dan ritual manual menyorok punca
selama berbulan, sambil membenarkan tiga bug migrasi lain terkumpul tanpa
dikesan.

Rujuk `docs/decisions/0001-boot4-modular-starters.md`.

Kalau anda mendapati diri anda memintas alat, siasat kenapa alat itu senyap.

## 6. Keselamatan

- **Jangan paste rahsia dalam chat.** Kunci Resend sebenar telah dipaste dua kali. Rotate kalau berlaku.
- **Production hidup.** VPN + DBeaver ke `p302_my` menyentuh data sebenar 71 SP. Ujian meninggalkan kesan.
- Endpoint auth (`/api/v1/auth/**`) berlaku **sebelum** konsep tenant wujud. Jangan tenant-scope.

---

## 7. Dokumentasi

Susunan repo:

```
docs/
  cara-kerja.md              ← dokumen ini
  domain/
    billing-rules.md         ← peraturan bil + bukti prod
    period-model.md
    ledger-accounting.md
  schema/
    erd.md
    migrations.md
  decisions/                 ← satu fail satu keputusan (ADR)
  evidence/
    prod-queries.md
```

**Setiap peraturan mesti ada bukti.** Query production atau hasil ujian. Kalau peraturan berubah, buktinya berubah.

**Setiap keputusan mesti ada sebab bertulis.** Bila partner bertanya "kenapa buang `last_charge`?", jawapannya ada — bukan dalam ingatan.

---

## 8. Ritma

- Chat panjang. Simpan penemuan dalam `docs/` **sebelum** chat tamat, bukan selepas.
- Bila chat baru dibuka: baca `docs/cara-kerja.md` + `docs/domain/*.md` dahulu.
- Claude tiada ingatan antara sesi kecuali apa yang tertulis. Repo ialah ingatan.

### Nota tentang jam panjang

Kerja terbaik 17 Julai datang dari interogasi production yang jelas — bukan dari jam yang panjang. Empat teori salah berturut-turut berlaku pada awal pagi, tergesa-gesa.

Rehat menghasilkan kod yang lebih baik daripada momentum.
