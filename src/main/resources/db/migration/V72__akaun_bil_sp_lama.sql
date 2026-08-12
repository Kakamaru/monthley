-- Akaun bil untuk SP yang didaftar sebelum onboarding satu transaksi.
--
-- ADR 0016 keputusan #12 menjadikan onboarding mencipta service_provider,
-- account, dan account_subscription bersama. SP yang didaftar sebelum itu
-- tiada billing_account_id — dicatat sebagai 'kes yatim' dalam ADR dan
-- ditangguh.
--
-- Ia menghalang kerja sekarang: ModuleEntitlementService menolak
-- permohonan modul daripada SP tanpa akaun bil, kerana membenarkannya
-- menghasilkan hak tanpa bil — SP menggunakan modul percuma selamanya dan
-- tiada apa yang memberi amaran.
--
-- Nombor akaun ialah kod SP: ia dijamin unik, dan billing_account_id
-- menyimpan hubungan sebenar — nombor tidak perlu memikul makna.

-- 1) Akaun bil untuk setiap SP yang belum ada.
INSERT INTO account
  (sp_code, account_no, account_name, charge_frequency, start_date, status,
   created_at, created_by, updated_at, version)
SELECT owner.sp_code, sp.sp_code, sp.name, 'MONTHLY', CURDATE(), 'ACTIVE',
       NOW(), 'migrasi', NOW(), 0
FROM   service_provider sp
CROSS  JOIN (SELECT sp_code FROM service_provider WHERE is_platform_owner = 1) owner
WHERE  sp.is_platform_owner = 0
  AND  sp.billing_account_id IS NULL
  AND  NOT EXISTS (
      SELECT 1 FROM account a
      WHERE a.sp_code = owner.sp_code AND a.account_no = sp.sp_code
  );

-- 2) Paut SP kepada akaunnya.
UPDATE service_provider sp
JOIN   service_provider owner ON owner.is_platform_owner = 1
JOIN   account a ON a.sp_code = owner.sp_code AND a.account_no = sp.sp_code
SET    sp.billing_account_id = a.id
WHERE  sp.is_platform_owner = 0 AND sp.billing_account_id IS NULL;

-- 3) Langganan pelan — bil bermula 1hb bulan berikutnya, mengikut peraturan
--    yang sama seperti kelulusan modul (ADR 0016). Backdating bermakna
--    invois untuk tempoh sebelum langganan wujud.
INSERT INTO account_subscription
  (sp_code, account_id, product_id, quantity, start_date, status, notes,
   created_at, created_by, updated_at, version)
SELECT owner.sp_code, sp.billing_account_id, sp.plan_product_id, 1,
       DATE_ADD(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL 1 MONTH),
       'ACTIVE', 'Pelan — dipautkan semasa migrasi V72',
       NOW(), 'migrasi', NOW(), 0
FROM   service_provider sp
CROSS  JOIN (SELECT sp_code FROM service_provider WHERE is_platform_owner = 1) owner
WHERE  sp.is_platform_owner = 0
  AND  sp.billing_account_id IS NOT NULL
  AND  sp.plan_product_id IS NOT NULL
  AND  NOT EXISTS (
      SELECT 1 FROM account_subscription s
      WHERE s.account_id = sp.billing_account_id
        AND s.product_id = sp.plan_product_id
        AND s.status = 'ACTIVE'
  );
