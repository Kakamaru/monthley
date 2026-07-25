-- ADR 0010 P3b — VIEW kepala penyata.
--
-- Kepala mesti datang daripada model, bukan daripada pemanggil. Jika setiap
-- pemanggil membina kepalanya sendiri, kita mendapat tiga kepala yang
-- menyimpang — iaitu tepat corak yang ADR ini wujud untuk menghalang
-- (keputusan 1: satu perkhidmatan, tiga pemanggil).
--
-- Modul statement mempunyai allowedDependencies = { shared }, jadi maklumat
-- SP dan akaun keluar melalui VIEW seperti dokumen dan padanan.
--
-- statement_title boleh diubah per SP (lalai 'Statement of Account'). Itu
-- sebab satu SP legacy mencetak 'Penyata Akaun' dan satu lagi 'STATEMENT OF
-- ACCOUNT'. Tanpanya kita mengalami regresi berbanding legacy.
--
-- language dan date_format dibawa supaya penulis boleh menyetempatkan nama
-- bulan dan format tarikh. Legacy tidak boleh — nama tempohnya teks tetap
-- yang ditaip semasa posting (CASE-004).
--
-- bank_code dipaparkan sebagaimana adanya; tiada jadual rujukan bank dalam
-- skema ini, tidak seperti ref_bank legacy.
CREATE OR REPLACE VIEW statement_header AS
SELECT a.id                       AS account_id,
       sp.sp_code                 AS sp_code,
       -- SP
       sp.name                    AS sp_name,
       sp.registration_no         AS sp_registration_no,
       sp.addr_line1              AS sp_addr_line1,
       sp.addr_line2              AS sp_addr_line2,
       sp.addr_line3              AS sp_addr_line3,
       sp.postcode                AS sp_postcode,
       sp.city                    AS sp_city,
       sp.state                   AS sp_state,
       sp.country                 AS sp_country,
       sp.phone                   AS sp_phone,
       sp.office_phone            AS sp_office_phone,
       sp.website                 AS sp_website,
       sp.contact_email           AS sp_email,
       sp.helpdesk_email          AS sp_helpdesk_email,
       sp.helpdesk_phone          AS sp_helpdesk_phone,
       sp.logo_url                AS sp_logo_url,
       sp.bank_code               AS sp_bank_code,
       sp.bank_account_no         AS sp_bank_account_no,
       sp.bank_account_name       AS sp_bank_account_name,
       -- akaun
       a.account_no               AS account_no,
       a.account_name             AS account_name,
       a.member_name              AS member_name,
       COALESCE(a.billto_name, a.member_name)   AS billto_name,
       COALESCE(a.billto_email, a.member_email) AS billto_email,
       a.billto_addr_line1        AS billto_addr_line1,
       a.billto_addr_line2        AS billto_addr_line2,
       a.billto_addr_line3        AS billto_addr_line3,
       a.billto_postcode          AS billto_postcode,
       a.billto_state             AS billto_state,
       a.billto_country           AS billto_country,
       -- tetapan
       COALESCE(NULLIF(TRIM(ds.statement_title), ''), 'Statement of Account')
                                  AS statement_title,
       COALESCE(bs.currency, 'MYR')   AS currency,
       COALESCE(bs.language, 'en')    AS language,
       bs.date_format             AS date_format,
       bs.tax_name                AS tax_name
FROM       account            a
JOIN       service_provider   sp ON sp.sp_code = a.sp_code
LEFT JOIN  sp_document_setting ds ON ds.sp_code = a.sp_code
LEFT JOIN  sp_billing_setting  bs ON bs.sp_code = a.sp_code;
