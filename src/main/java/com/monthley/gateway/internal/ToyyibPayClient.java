package com.monthley.gateway.internal;

import com.monthley.gateway.api.GatewayPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Klien ToyyibPay.
 *
 * Kelayakan (User Secret Key, Category Code) hidup pada sp_payment_setting
 * dan DISULITKAN — setiap SP mempunyai akaun ToyyibPay sendiri.
 *
 * Dua perkara khusus ToyyibPay yang mudah tersalah:
 *
 *   AMAUN DALAM SEN. billAmount ialah integer sen — RM10.50 dihantar
 *   sebagai 1050. Menghantar 10.50 mencipta bil sepuluh sen lima puluh.
 *
 *   TIADA TANDATANGAN pada callback. Pengesahan dibuat dengan memanggil
 *   balik getBillTransactions.
 */
@Component
class ToyyibPayClient implements GatewayPort {

    private final GatewayCredentials creds;
    private final RestClient http = RestClient.create();

    private final String sandboxUrl;
    private final String liveUrl;

    ToyyibPayClient(GatewayCredentials creds,
                    @Value("${monthley.gateway.toyyibpay.sandbox-url}") String sandboxUrl,
                    @Value("${monthley.gateway.toyyibpay.live-url}") String liveUrl) {
        this.creds = creds;
        this.sandboxUrl = sandboxUrl;
        this.liveUrl = liveUrl;
    }

    @Override
    public String code() { return "TP"; }

    private String base(String spCode) {
        return creds.isSandbox(spCode) ? sandboxUrl : liveUrl;
    }

    @Override
    public BillCreated createBill(NewBill r) {
        GatewayCredentials.Creds c = creds.forSp(r.spCode());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("userSecretKey", c.secretKey());
        form.add("categoryCode", c.categoryCode());
        form.add("billName", potong(r.description(), 30));
        form.add("billDescription", potong(r.description(), 100));

        // 1 = amaun tetap. Pelanggan tidak boleh menukar jumlah yang
        // hendak dibayar — invois sudah menetapkannya.
        form.add("billPriceSetting", "1");
        form.add("billPayorInfo", "1");

        // SEN, bukan ringgit.
        form.add("billAmount", String.valueOf(
                r.amount().setScale(2, RoundingMode.HALF_UP)
                         .multiply(BigDecimal.valueOf(100)).intValueExact()));

        form.add("billReturnUrl", r.returnUrl());
        form.add("billCallbackUrl", r.callbackUrl());

        // Rujukan KITA — kembali dalam callback sebagai order_id.
        form.add("billExternalReferenceNo", r.ourRef());

        form.add("billTo", r.payerName());
        form.add("billEmail", r.payerEmail());
        form.add("billPhone", r.payerPhone() == null ? "" : r.payerPhone());
        form.add("billSplitPayment", "0");
        form.add("billPaymentChannel", "0");   // 0 = FPX
        form.add("billChargeToCustomer", "1"); // yuran ditanggung pembayar

        // ToyyibPay memulangkan ARRAY pada kejayaan, OBJEK pada ralat —
        // jadi respons dibaca sebagai teks dahulu dan bentuknya diperiksa.
        List<Map<String, Object>> resp;
        try {
            resp = http.post()
                    .uri(base(r.spCode()) + "/index.php/api/createBill")
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            // Respons berbentuk objek bermakna ToyyibPay menolak permintaan.
            throw new IllegalStateException(
                    "ToyyibPay menolak permintaan bil. Semak kunci dan kod kategori.", e);
        }

        if (resp == null || resp.isEmpty()) {
            throw new IllegalStateException("ToyyibPay tidak memulangkan bil.");
        }
        Object kod = resp.get(0).get("BillCode");
        if (kod == null || kod.toString().isBlank()) {
            throw new IllegalStateException("ToyyibPay tidak memulangkan BillCode.");
        }
        return new BillCreated(kod.toString(), base(r.spCode()) + "/" + kod);
    }

    @Override
    public BillTxn fetchTransaction(String spCode, String billCode) {
        GatewayCredentials.Creds c = creds.forSp(spCode);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("billCode", billCode);
        // billpaymentStatus dibiarkan kosong supaya SEMUA percubaan
        // dipulangkan — menapis kepada '1' menyembunyikan percubaan gagal,
        // dan itulah yang diperlukan semasa menyiasat.
        form.add("billpaymentStatus", "");

        List<Map<String, Object>> resp;
        try {
            resp = http.post()
                    .uri(base(spCode) + "/index.php/api/getBillTransactions")
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Gagal menyemak transaksi pada ToyyibPay.", e);
        }

        if (resp == null || resp.isEmpty()) {
            return new BillTxn(false, null, null, "NONE", "[]");
        }

        // Cari percubaan BERJAYA. Satu bil boleh mempunyai beberapa
        // percubaan — pelanggan gagal, cuba lagi, berjaya.
        for (Map<String, Object> t : resp) {
            if ("1".equals(teks(t.get("billpaymentStatus")))) {
                return new BillTxn(
                        true,
                        teks(t.get("billpaymentInvoiceNo")),
                        new BigDecimal(teks(t.get("billpaymentAmount"))
                                .replace(",", "").trim()),
                        "SUCCESS",
                        t.toString());
            }
        }
        Map<String, Object> akhir = resp.get(resp.size() - 1);
        return new BillTxn(false, null, null,
                teks(akhir.get("billpaymentStatus")), resp.toString());
    }

    private static String teks(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String potong(String v, int max) {
        if (v == null) return "";
        String t = v.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
