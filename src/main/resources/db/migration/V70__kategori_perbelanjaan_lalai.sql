-- Kategori perbelanjaan lalai + akaun GL sepadan.
--
-- Senarai daripada penggunaan sebenar NGO (Persatuan PERANTAU): 18
-- kategori, ~95 jenis. Ia menggantikan tiga kategori JMB yang diseed
-- secara manual semasa pembangunan.
--
-- SETIAP kategori induk mendapat akaun GL SENDIRI dalam julat 5100–5999.
-- Keputusan awal ialah tiga akaun sahaja supaya Untung Rugi ringkas,
-- tetapi itu dibuat ketika hanya ada tiga kategori. Dengan 18, memaksa
-- semuanya ke dalam tiga akaun bermakna penyata kepada AGM memaparkan
-- 'Pentadbiran RM15,000' dan menyembunyikan bahawa RM9,050 daripadanya
-- ialah Acara & Aktiviti. Nombor yang tidak boleh dipecahkan tanpa
-- membuka lejar bukan penyata yang berguna.
--
-- Emoji dikekalkan dalam nama kategori: ia daripada sistem sebenar dan
-- membantu pengecaman pantas dalam dropdown yang panjang.

-- ---------------------------------------------------------------------------
-- 1) Akaun GL untuk setiap kategori
-- ---------------------------------------------------------------------------
INSERT INTO chart_of_accounts
  (sp_code, code, name, account_type, normal_side, is_control, status,
   created_at, updated_at, version)
SELECT sp.sp_code, x.code, x.name, 'EXPENSE', 'DEBIT', 0, 'ACTIVE', NOW(), NOW(), 0
FROM   service_provider sp
CROSS  JOIN (
    SELECT '5110' AS code, 'Pengangkutan & Perjalanan' AS name
    UNION ALL SELECT '5120', 'Penginapan'
    UNION ALL SELECT '5130', 'Makanan & Minuman'
    UNION ALL SELECT '5140', 'Operasi Pejabat'
    UNION ALL SELECT '5150', 'Telekomunikasi'
    UNION ALL SELECT '5160', 'Mesyuarat & Program'
    UNION ALL SELECT '5170', 'Acara & Aktiviti'
    UNION ALL SELECT '5180', 'Elaun & Tenaga Kerja'
    UNION ALL SELECT '5190', 'Latihan & Pendidikan'
    UNION ALL SELECT '5210', 'ICT & Teknologi'
    UNION ALL SELECT '5220', 'Kebajikan & Perubatan'
    UNION ALL SELECT '5230', 'Hadiah & Cenderamata'
    UNION ALL SELECT '5240', 'Promosi & Pemasaran'
    UNION ALL SELECT '5250', 'Aset & Peralatan'
    UNION ALL SELECT '5260', 'Kebajikan & Sumbangan'
    UNION ALL SELECT '5310', 'Pengurusan'
    UNION ALL SELECT '5320', 'Keagamaan & Kerohanian'
    UNION ALL SELECT '5990', 'Lain-lain'
) x
WHERE NOT EXISTS (
    SELECT 1 FROM chart_of_accounts c
    WHERE c.sp_code = sp.sp_code AND c.code = x.code
);

-- ---------------------------------------------------------------------------
-- 2) Kategori induk, dipaut ke akaun GL masing-masing
-- ---------------------------------------------------------------------------
-- Hanya untuk SP yang MELANGGAN modul. SP tanpa hak tidak sepatutnya
-- mempunyai kategori perbelanjaan yang tidak boleh digunakan.
INSERT INTO exp_category
  (sp_code, name, parent_id, gl_account_id, sort_order, status,
   created_at, created_by, updated_at, version)
SELECT m.sp_code, x.nama, NULL, c.id, x.urut, 'ACTIVE', NOW(), 'seed', NOW(), 0
FROM   sp_module m
JOIN   (
    SELECT '🚗 Pengangkutan & Perjalanan' AS nama, '5110' AS gl, 1 AS urut
    UNION ALL SELECT '🛏️ Penginapan',              '5120', 2
    UNION ALL SELECT '🍽️ Makanan & Minuman',       '5130', 3
    UNION ALL SELECT '🖨️ Operasi Pejabat',         '5140', 4
    UNION ALL SELECT '📞 Telekomunikasi',          '5150', 5
    UNION ALL SELECT '🤝 Mesyuarat & Program',     '5160', 6
    UNION ALL SELECT '🎤 Acara & Aktiviti',        '5170', 7
    UNION ALL SELECT '👷 Elaun & Tenaga Kerja',    '5180', 8
    UNION ALL SELECT '📚 Latihan & Pendidikan',    '5190', 9
    UNION ALL SELECT '💻 ICT & Teknologi',         '5210', 10
    UNION ALL SELECT '🩺 Kebajikan & Perubatan',   '5220', 11
    UNION ALL SELECT '🎁 Hadiah & Cenderamata',    '5230', 12
    UNION ALL SELECT '📢 Promosi & Pemasaran',     '5240', 13
    UNION ALL SELECT '🏗️ Aset & Peralatan',        '5250', 14
    UNION ALL SELECT '🕌 Kebajikan & Sumbangan',   '5260', 15
    UNION ALL SELECT '💼 Pengurusan',              '5310', 16
    UNION ALL SELECT '📿 Keagamaan & Kerohanian',  '5320', 17
    UNION ALL SELECT '📦 Lain-lain',               '5990', 18
) x
JOIN   chart_of_accounts c ON c.sp_code = m.sp_code AND c.code = x.gl
WHERE  m.module_code = 'PERBELANJAAN' AND m.status = 'ACTIVE'
  AND  NOT EXISTS (
      SELECT 1 FROM exp_category e
      WHERE e.sp_code = m.sp_code AND e.name = x.nama AND e.parent_id IS NULL
  );

-- ---------------------------------------------------------------------------
-- 3) Jenis (anak) — mewarisi GL daripada induknya
-- ---------------------------------------------------------------------------
INSERT INTO exp_category
  (sp_code, name, parent_id, gl_account_id, sort_order, status,
   created_at, created_by, updated_at, version)
SELECT m.sp_code, y.nama, p.id, NULL, y.urut, 'ACTIVE', NOW(), 'seed', NOW(), 0
FROM   sp_module m
JOIN   (
    SELECT 'Minyak petrol/diesel' AS nama, '🚗 Pengangkutan & Perjalanan' AS induk, 1 AS urut
    UNION ALL SELECT 'Tol',                        '🚗 Pengangkutan & Perjalanan', 2
    UNION ALL SELECT 'Parkir',                     '🚗 Pengangkutan & Perjalanan', 3
    UNION ALL SELECT 'Tambang teksi/e-hailing',    '🚗 Pengangkutan & Perjalanan', 4
    UNION ALL SELECT 'Tiket bas',                  '🚗 Pengangkutan & Perjalanan', 5
    UNION ALL SELECT 'Tiket kapal terbang',        '🚗 Pengangkutan & Perjalanan', 6
    UNION ALL SELECT 'Sewaan kenderaan',           '🚗 Pengangkutan & Perjalanan', 7

    UNION ALL SELECT 'Hotel',                      '🛏️ Penginapan', 1
    UNION ALL SELECT 'Homestay',                   '🛏️ Penginapan', 2
    UNION ALL SELECT 'Resort',                     '🛏️ Penginapan', 3
    UNION ALL SELECT 'Penginapan transit',         '🛏️ Penginapan', 4

    UNION ALL SELECT 'Sarapan',                    '🍽️ Makanan & Minuman', 1
    UNION ALL SELECT 'Makan tengah hari',          '🍽️ Makanan & Minuman', 2
    UNION ALL SELECT 'Makan malam',                '🍽️ Makanan & Minuman', 3
    UNION ALL SELECT 'Minum petang',               '🍽️ Makanan & Minuman', 4
    UNION ALL SELECT 'Jamuan',                     '🍽️ Makanan & Minuman', 5
    UNION ALL SELECT 'Katering',                   '🍽️ Makanan & Minuman', 6

    UNION ALL SELECT 'Alat tulis',                 '🖨️ Operasi Pejabat', 1
    UNION ALL SELECT 'Kertas',                     '🖨️ Operasi Pejabat', 2
    UNION ALL SELECT 'Pencetak',                   '🖨️ Operasi Pejabat', 3
    UNION ALL SELECT 'Dakwat printer',             '🖨️ Operasi Pejabat', 4
    UNION ALL SELECT 'Fotostat',                   '🖨️ Operasi Pejabat', 5
    UNION ALL SELECT 'Binding',                    '🖨️ Operasi Pejabat', 6
    UNION ALL SELECT 'Sewa pejabat',               '🖨️ Operasi Pejabat', 7
    UNION ALL SELECT 'Bil elektrik',               '🖨️ Operasi Pejabat', 8
    UNION ALL SELECT 'Bil air',                    '🖨️ Operasi Pejabat', 9

    UNION ALL SELECT 'Bil telefon',                '📞 Telekomunikasi', 1
    UNION ALL SELECT 'Internet',                   '📞 Telekomunikasi', 2
    UNION ALL SELECT 'Data mudah alih',            '📞 Telekomunikasi', 3
    UNION ALL SELECT 'Langganan aplikasi',         '📞 Telekomunikasi', 4

    UNION ALL SELECT 'Sewa dewan',                 '🤝 Mesyuarat & Program', 1
    UNION ALL SELECT 'Makanan mesyuarat',          '🤝 Mesyuarat & Program', 2
    UNION ALL SELECT 'Bahan program',              '🤝 Mesyuarat & Program', 3
    UNION ALL SELECT 'Cenderamata',                '🤝 Mesyuarat & Program', 4
    UNION ALL SELECT 'Backdrop',                   '🤝 Mesyuarat & Program', 5

    UNION ALL SELECT 'Sistem PA',                  '🎤 Acara & Aktiviti', 1
    UNION ALL SELECT 'Kanopi',                     '🎤 Acara & Aktiviti', 2
    UNION ALL SELECT 'Khemah',                     '🎤 Acara & Aktiviti', 3
    UNION ALL SELECT 'Meja kerusi',                '🎤 Acara & Aktiviti', 4
    UNION ALL SELECT 'Pengacara majlis',           '🎤 Acara & Aktiviti', 5
    UNION ALL SELECT 'Persembahan',                '🎤 Acara & Aktiviti', 6

    UNION ALL SELECT 'Elaun perjalanan',           '👷 Elaun & Tenaga Kerja', 1
    UNION ALL SELECT 'Elaun harian',               '👷 Elaun & Tenaga Kerja', 2
    UNION ALL SELECT 'Saguhati petugas',           '👷 Elaun & Tenaga Kerja', 3
    UNION ALL SELECT 'Upah pekerja sambilan',      '👷 Elaun & Tenaga Kerja', 4
    UNION ALL SELECT 'Honorarium',                 '👷 Elaun & Tenaga Kerja', 5
    UNION ALL SELECT 'Caruman KWSP/SOCSO',         '👷 Elaun & Tenaga Kerja', 6

    UNION ALL SELECT 'Yuran kursus',               '📚 Latihan & Pendidikan', 1
    UNION ALL SELECT 'Seminar',                    '📚 Latihan & Pendidikan', 2
    UNION ALL SELECT 'Bengkel',                    '📚 Latihan & Pendidikan', 3
    UNION ALL SELECT 'Bahan latihan',              '📚 Latihan & Pendidikan', 4
    UNION ALL SELECT 'Sijil',                      '📚 Latihan & Pendidikan', 5

    UNION ALL SELECT 'Domain',                     '💻 ICT & Teknologi', 1
    UNION ALL SELECT 'Hosting',                    '💻 ICT & Teknologi', 2
    UNION ALL SELECT 'Lesen perisian',             '💻 ICT & Teknologi', 3
    UNION ALL SELECT 'Pembelian perkakasan komputer', '💻 ICT & Teknologi', 4

    UNION ALL SELECT 'Rawatan perubatan',          '🩺 Kebajikan & Perubatan', 1
    UNION ALL SELECT 'Ubat-ubatan',                '🩺 Kebajikan & Perubatan', 2
    UNION ALL SELECT 'Saringan kesihatan',         '🩺 Kebajikan & Perubatan', 3

    UNION ALL SELECT 'Hamper',                     '🎁 Hadiah & Cenderamata', 1
    UNION ALL SELECT 'Plak',                       '🎁 Hadiah & Cenderamata', 2
    UNION ALL SELECT 'Medal',                      '🎁 Hadiah & Cenderamata', 3
    UNION ALL SELECT 'Hadiah pertandingan',        '🎁 Hadiah & Cenderamata', 4

    UNION ALL SELECT 'Banner',                     '📢 Promosi & Pemasaran', 1
    UNION ALL SELECT 'Bunting',                    '📢 Promosi & Pemasaran', 2
    UNION ALL SELECT 'Poster',                     '📢 Promosi & Pemasaran', 3
    UNION ALL SELECT 'Iklan media sosial',         '📢 Promosi & Pemasaran', 4
    UNION ALL SELECT 'Percetakan',                 '📢 Promosi & Pemasaran', 5
    UNION ALL SELECT 'Fotografi & videografi',     '📢 Promosi & Pemasaran', 6

    UNION ALL SELECT 'Pembelian peralatan',        '🏗️ Aset & Peralatan', 1
    UNION ALL SELECT 'Penyelenggaraan',            '🏗️ Aset & Peralatan', 2
    UNION ALL SELECT 'Pembaikan aset',             '🏗️ Aset & Peralatan', 3
    UNION ALL SELECT 'Perabot',                    '🏗️ Aset & Peralatan', 4

    UNION ALL SELECT 'Bantuan asnaf',              '🕌 Kebajikan & Sumbangan', 1
    UNION ALL SELECT 'Sumbangan bencana',          '🕌 Kebajikan & Sumbangan', 2
    UNION ALL SELECT 'Program CSR',                '🕌 Kebajikan & Sumbangan', 3
    UNION ALL SELECT 'Bantuan kebajikan ahli',     '🕌 Kebajikan & Sumbangan', 4

    UNION ALL SELECT 'Pendaftaran organisasi',     '💼 Pengurusan', 1
    UNION ALL SELECT 'Fi bank',                    '💼 Pengurusan', 2
    UNION ALL SELECT 'Caj transaksi',              '💼 Pengurusan', 3
    UNION ALL SELECT 'Yuran profesional',          '💼 Pengurusan', 4
    UNION ALL SELECT 'Yuran audit',                '💼 Pengurusan', 5
    UNION ALL SELECT 'Yuran guaman',               '💼 Pengurusan', 6
    UNION ALL SELECT 'Duti setem',                 '💼 Pengurusan', 7
    UNION ALL SELECT 'Insurans',                   '💼 Pengurusan', 8

    UNION ALL SELECT 'Program agama',              '📿 Keagamaan & Kerohanian', 1
    UNION ALL SELECT 'Yuran ustaz/penceramah',     '📿 Keagamaan & Kerohanian', 2
    UNION ALL SELECT 'Sewa surau/masjid',          '📿 Keagamaan & Kerohanian', 3
    UNION ALL SELECT 'Bahan bacaan agama',         '📿 Keagamaan & Kerohanian', 4
    UNION ALL SELECT 'Kelas agama',                '📿 Keagamaan & Kerohanian', 5

    UNION ALL SELECT 'Perbelanjaan pelbagai',      '📦 Lain-lain', 1
    UNION ALL SELECT 'Kos tidak dijangka',         '📦 Lain-lain', 2
) y
JOIN   exp_category p ON p.sp_code = m.sp_code AND p.name = y.induk AND p.parent_id IS NULL
WHERE  m.module_code = 'PERBELANJAAN' AND m.status = 'ACTIVE'
  AND  NOT EXISTS (
      SELECT 1 FROM exp_category e
      WHERE e.sp_code = m.sp_code AND e.name = y.nama AND e.parent_id = p.id
  );
