# Pemasangan VPS

Langkah untuk menyediakan Monthley pada pelayan baharu. Setiap bahagian
boleh dijalankan berasingan; skrip yang disebut adalah idempoten.

---

## 1. Keselamatan asas

Log SSH pada pemasangan pertama menunjukkan 42,866 percubaan log masuk
gagal — bot mengimbas port 22 sepanjang masa, dan kata laluan root hanya
perlu tepat sekali.

Pasang fail2ban, salin kunci SSH, UJI kunci itu, kemudian matikan log
masuk kata laluan dalam sshd_config.d/99-monthley.conf.

Cloud-init pada sesetengah pembekal menetapkan PasswordAuthentication yes
dalam 50-cloud-init.conf. SSH mengambil nilai PERTAMA, jadi fail itu
menang atas 99-. Nyahaktifkannya dengan menamakan semula kepada
.disabled.

---

## 2. Pangkalan data

MySQL 8 atau MariaDB, mendengar pada 127.0.0.1 sahaja. Cipta DB monthley
dengan utf8mb4, pengguna monthley, dan simpan kata laluan di
/root/.monthley-db-pass dengan kebenaran 600.

NOTA MySQL 8: ONLY_FULL_GROUP_BY adalah lalai dan lebih ketat daripada
MySQL 9. Query dengan HAVING tanpa GROUP BY gagal — ini pernah lulus
pembangunan dan gagal selepas deploy.

---

## 3. Storan objek

Jalankan ops/pasang-minio.sh. Skrip mencetak kunci dan blok Nginx yang
perlu ditambah.

---

## 4. Backend

Cipta /opt/monthley/app, /opt/monthley/static, /opt/monthley/logs.

Fail unit monthley.service menunjuk ke /opt/monthley/app/monthley.jar
dengan EnvironmentFile=/opt/monthley/app/monthley.env.

DEPLOY MESTI MENGHENTIKAN SERVIS SEBELUM MENUKAR JAR. JVM memuatkan kelas
secara malas dari fail JAR; menukarnya di bawah proses yang hidup
menyebabkan NoClassDefFoundError untuk kelas yang belum dimuatkan.
Gejalanya menyesatkan — ralat rawak tentang kelas Tomcat dan Hibernate
yang kelihatan seperti masalah kelayakan gerbang.

ops/deploy.sh melakukannya dengan betul.

---

## 5. Nginx

Tiga perkara selain blok /files/ daripada skrip MinIO:

| Tetapan | Nilai | Sebab |
|---|---|---|
| client_max_body_size | 25m | Muat naik penyata dan CSV caj penggunaan |
| proxy_read_timeout pada /api/ | 120s | Callback gerbang boleh lambat; memutuskannya bermakna bayaran hilang |
| try_files pada / | uri, uri/, /index.html | Angular ialah SPA |

---

## Rujukan konfigurasi

Semua dalam /opt/monthley/app/monthley.env, kebenaran 600.

| Pembolehubah | Contoh | Nota |
|---|---|---|
| MONTHLEY_DB_URL | jdbc:mysql://localhost:3306/monthley | Driver MySQL walaupun pelayan MariaDB |
| MONTHLEY_DB_USER | monthley | |
| MONTHLEY_DB_PASSWORD | dari /root/.monthley-db-pass | |
| MONTHLEY_APP_URL | https://monthley.perantau.org.my | Tanpa garis miring hujung. Digunakan untuk pautan e-mel, URL kembali gerbang, dan URL fail |
| MONTHLEY_CORS_ORIGINS | https://monthley.perantau.org.my | Dipisah koma. Pelayar menghantar Origin; curl tidak, jadi CORS yang salah kelihatan seperti masalah pengesahan |
| MONTHLEY_SUPERADMIN_PASSWORD | dari /root/.monthley-superadmin-pass | Aplikasi ENGGAN boot tanpanya di luar pembangunan |
| MONTHLEY_MASTER_KEY | openssl rand -base64 32 | AES-256 untuk kelayakan gerbang. MENUKARNYA MENJADIKAN KUNCI GERBANG SEDIA ADA TIDAK BOLEH DIBACA |
| MONTHLEY_RESEND_KEY | re_... | Kosong = e-mel dilog, tidak dihantar |
| MONTHLEY_EMAIL_FROM | Monthley (monthley@perantau.org.my) | Domain MESTI disahkan dalam Resend |
| MONTHLEY_STORAGE_ENDPOINT | http://127.0.0.1:9000 | |
| MONTHLEY_STORAGE_KEY | monthley | |
| MONTHLEY_STORAGE_SECRET | dari /root/.minio-pass | Kosong = muat naik gagal dengan mesej jelas, aplikasi tetap boot |

### Rahsia pada pelayan

| Fail | Kandungan |
|---|---|
| /root/.monthley-db-pass | Kata laluan DB |
| /root/.monthley-superadmin-pass | Kata laluan superadmin |
| /root/.minio-pass | Rahsia MinIO |

Ketiga-tiganya 600 dan di luar git.

---

## Selepas pemasangan

Flyway menjalankan migrasi semasa boot pertama. Sahkan versi tertinggi
dalam flyway_schema_history dengan success = 1.

V77 mencipta SP platform (SP0000) dengan produk pelan — tanpanya,
onboarding SP pertama tidak boleh diselesaikan kerana tiada pelan untuk
dipilih.

Superadmin log masuk dengan superadmin@monthley.my dan kata laluan
daripada persekitaran.
