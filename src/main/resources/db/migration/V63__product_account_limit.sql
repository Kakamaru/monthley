-- ADR 0016 peringkat B1: kuota akaun berpindah dari service_plan ke product.
--
-- service_plan cuba menjadi katalog kedua, tetapi katalog sebenar ialah
-- product bawah SP platform. Gejalanya sudah muncul: produk P400 wujud tetapi
-- tiada baris service_plan yang sepadan, jadi pelan itu tidak boleh dipilih
-- semasa onboarding walaupun produknya ada. Setiap pelan baharu perlu dicipta
-- dua kali, dan sekali terlupa, ia hilang secara senyap.
--
-- Harga juga berbeza antara kedua-duanya (service_plan P300 = RM300,
-- product P300 = RM80). Yang betul ialah harga produk — itulah yang masuk ke
-- invois. service_plan memaparkan angka yang tidak pernah dibil.
--
-- Selepas ini: produk kategori BASIC dengan account_limit terisi ialah pelan;
-- tanpa account_limit ialah item sekali sahaja (onboarding, migrasi).
--
-- service_plan SENGAJA dikekalkan buat masa ini. Tiga bacaan backend masih
-- bergantung padanya dan TIADA ujian melindunginya — jadi ia dialihkan
-- selepas ujian ditulis, bukan sekarang (corak V61: sahkan dahulu, gugur
-- kemudian).

ALTER TABLE product
  ADD COLUMN account_limit INT NULL AFTER term_days;

-- Kuota bagi produk pelan sedia ada. Dicari melalui bendera pemilik platform,
-- bukan kod SP — itulah sebab bendera itu wujud (ADR 0016).
UPDATE product p
JOIN   service_provider sp ON sp.sp_code = p.sp_code AND sp.is_platform_owner = 1
SET    p.account_limit = CAST(SUBSTRING(p.code, 2) AS UNSIGNED)
WHERE  p.code REGEXP '^P[0-9]+$';

-- Pautan SP kepada produk pelannya. Menggantikan service_plan_id.
ALTER TABLE service_provider
  ADD COLUMN plan_product_id BIGINT NULL AFTER service_plan_id,
  ADD CONSTRAINT fk_sp_plan_product
      FOREIGN KEY (plan_product_id) REFERENCES product(id);

-- SP0001 sedang guna Pakej 300; pautkan ke produk yang sepadan supaya ia
-- tidak menjadi SP tanpa pelan selepas service_plan digugurkan.
UPDATE service_provider sp
JOIN   service_provider owner ON owner.is_platform_owner = 1
JOIN   product p ON p.sp_code = owner.sp_code AND p.code = 'P300'
JOIN   service_plan pl ON pl.id = sp.service_plan_id AND pl.code = 'P300'
SET    sp.plan_product_id = p.id;

-- Lajur ini ditambah dalam V62 sebelum keputusan membuang service_plan.
-- Digugurkan sekarang supaya tiada lajur mati ditinggalkan.
ALTER TABLE service_plan
  DROP FOREIGN KEY fk_plan_product,
  DROP COLUMN product_id;
