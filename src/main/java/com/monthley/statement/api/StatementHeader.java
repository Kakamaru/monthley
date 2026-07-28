package com.monthley.statement.api;

/**
 * Kepala penyata — datang daripada MODEL, bukan daripada pemanggil.
 *
 * Jika setiap pemanggil membina kepalanya sendiri, kita mendapat tiga
 * kepala yang menyimpang — corak yang ADR 0010 wujud untuk menghalang.
 *
 * language dan dateFormat membolehkan penulis menyetempatkan nama bulan
 * dan tarikh. Legacy tidak boleh: nama tempohnya teks tetap yang ditaip
 * semasa posting.
 *
 * Kebanyakan medan boleh NULL — SP mengisi profil mereka secara
 * berperingkat. Penulis mesti melangkau baris kosong, bukan mencetak
 * ruang kosong atau 'null'. Legacy mencetak '-' untuk medan tiada, yang
 * kelihatan seperti data.
 */
public record StatementHeader(
        String statementTitle,
        String currency,
        String language,
        String dateFormat,
        String taxName,
        // SP
        String spName,
        String spRegistrationNo,
        String spAddrLine1,
        String spAddrLine2,
        String spAddrLine3,
        String spPostcode,
        String spCity,
        String spState,
        String spCountry,
        String spPhone,
        String spWebsite,
        String spEmail,
        String spHelpdeskEmail,
        String spHelpdeskPhone,
        String spLogoUrl,
        String spBankCode,
        String spBankAccountNo,
        String spBankAccountName,
        // akaun
        String accountNo,
        String accountName,
        String memberName,
        String billtoName,
        String billtoEmail,
        String billtoAddrLine1,
        String billtoAddrLine2,
        String billtoAddrLine3,
        String billtoPostcode,
        String billtoState,
        String billtoCountry) {

    /**
     * Salinan dengan corak tarikh berbeza.
     *
     * Record dengan 33 medan bermakna menukar satu medan memerlukan
     * menaip 32 yang lain — dan setiap kali medan ditambah, setiap
     * pembinaan sedemikian pecah. Kaedah ini menyimpan senarai medan
     * di SATU tempat.
     */
    public StatementHeader withDateFormat(String pattern) {
        return new StatementHeader(
                statementTitle, currency, language, pattern, taxName,
                spName, spRegistrationNo, spAddrLine1, spAddrLine2, spAddrLine3,
                spPostcode, spCity, spState, spCountry, spPhone, spWebsite,
                spEmail, spHelpdeskEmail, spHelpdeskPhone, spLogoUrl,
                spBankCode, spBankAccountNo, spBankAccountName,
                accountNo, accountName, memberName,
                billtoName, billtoEmail, billtoAddrLine1, billtoAddrLine2,
                billtoAddrLine3, billtoPostcode, billtoState, billtoCountry);
    }
}
