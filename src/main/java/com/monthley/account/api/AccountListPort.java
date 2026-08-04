package com.monthley.account.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Senarai Akaun untuk laporan.
 *
 * Modul account memiliki akaun; modul statement merendernya. Corak sama
 * seperti CollectionReportPort.
 */
public interface AccountListPort {

    /**
     * Satu akaun.
     *
     * Alamat digabungkan menjadi SATU rentetan di sini, bukan empat
     * baris berasingan: laporan legacy mempunyai lima belas lajur dan
     * teksnya bertindih sehingga tidak boleh dibaca. Excel mendapat
     * medan mentah; PDF mendapat sesuatu yang muat.
     */
    record Row(String accountNo, String accountName,
               /** No. KP/pendaftaran — Excel sahaja; PDF tiada ruang. */
               String idNo,
               String issueTo, String phone, String email,
               String address, String postcode, String state,
               String categoryName, String status,
               BigDecimal balance) {}

    record Query(String spCode, Boolean active, Long categoryId, String search) {}

    record Result(List<Row> rows, BigDecimal totalBalance,
                  int activeCount, int inactiveCount) {}

    Result accountList(Query q);
}
