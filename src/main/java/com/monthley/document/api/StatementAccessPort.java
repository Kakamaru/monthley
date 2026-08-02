package com.monthley.document.api;

import java.util.Optional;

/**
 * Pautan awam kepada PENYATA akaun (ADR 0014 P3, V57).
 *
 * Pelanggan yang menerima e-mel penyata mungkin TIADA akaun portal —
 * ramai tidak mahu mendaftar. Pautan mesti berfungsi tanpa log masuk,
 * tanpa JWT dan tanpa TenantContext.
 *
 * BERASINGAN daripada DocumentAccessPort kerana penyata bukan dokumen:
 * ia unjuran atas julat tarikh (ADR 0010) dan tiada baris dalam
 * financial_document. CASE-006 menunjukkan apa yang berlaku apabila
 * perbezaan itu diabaikan — legacy mencipta dokumen 'P' hantu untuk
 * setiap e-mel, 51 rekod bukan-kewangan pada satu akaun.
 *
 * Duduk dalam modul DOCUMENT, bersebelahan DocumentAccessPort.
 *
 * Pilihan pertama ialah modul account — jadual mempunyai FK ke account,
 * dan modul yang memiliki jadual patut memiliki rujukannya. Tetapi
 * AccountController sudah menggunakan StatementPort untuk endpoint
 * /{id}/statement, jadi statement -> account mencipta KITARAN yang
 * ModularityTests tolak.
 *
 * ModularityTests hanya menangkapnya selepas endpoint ditulis:
 * pengisytiharan allowedDependencies sahaja tidak mencukupi, ia
 * memerlukan penggunaan SEBENAR.
 *
 * FK ke account kekal — itu kekangan data, bukan kebergantungan kod.
 * document tidak mengimport apa-apa daripada account.
 */
public interface StatementAccessPort {

    /**
     * Token untuk penyata akaun ini bagi tahun ini, cipta jika belum ada.
     *
     * SATU token per (akaun, tahun). Penyata dihantar setiap kali bil
     * dijana — dua belas kali setahun untuk akaun bulanan. Token per
     * penghantaran bermakna sepuluh ribu akaun menghasilkan seratus dua
     * puluh ribu baris setahun untuk mengakses data yang sama.
     *
     * Pautan yang sama sepanjang tahun bermakna e-mel Januari masih
     * berfungsi pada Disember, dan penyata yang dibukanya menunjukkan
     * keadaan SEMASA.
     */
    String tokenFor(String spCode, long accountId, int year);

    /**
     * Selesaikan token, dan rekod bahawa ia dilihat.
     *
     * Kosong jika token tidak wujud ATAU sudah dibatalkan — pemanggil
     * tidak boleh membezakan kedua-duanya, supaya token tidak boleh
     * dibilang.
     */
    Optional<ResolvedStatement> resolve(String token);

    record ResolvedStatement(String spCode, long accountId, int year) {}

    /**
     * Matikan pautan.
     *
     * Berbeza daripada dokumen: penyata tidak boleh DIBATALKAN — ia
     * unjuran, bukan rekod. revoke di sini ialah campur tangan manual
     * untuk kes akaun bertukar pemilik atau alamat e-mel salah.
     */
    void revoke(long accountId, int year);
}
