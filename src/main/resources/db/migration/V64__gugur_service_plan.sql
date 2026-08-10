-- ADR 0016 peringkat B5: buang service_plan sepenuhnya.
--
-- Katalog sebenar ialah product bawah SP platform. service_plan ialah
-- katalog kedua yang perlu diselenggara selari — dan ia sudah menyimpang:
-- produk P400 tiada padanan, dan harga P300 berbeza (RM300 vs RM80 sebenar).
--
-- billing_plan digugurkan bersama: tiada pelan tahunan untuk SP. SP yang
-- mahu dibil setahun sekali diuruskan pada peringkat langganan. Membiarkan
-- lajur itu bermakna nilai 'YEARLY' boleh disimpan dan tidak bermakna
-- apa-apa — corak CASE-008.
--
-- Disahkan sebelum digugurkan: tiada bacaan tinggal dalam kod mahupun
-- ujian; tiga bacaan dialihkan dalam B3, laluan tulis dalam B4, dan nama
-- DTO diperbetulkan supaya tiada rujukan menipu (corak V61).

ALTER TABLE service_provider
  DROP FOREIGN KEY fk_sp_service_plan,
  DROP COLUMN service_plan_id,
  DROP COLUMN billing_plan;

DROP TABLE service_plan;
