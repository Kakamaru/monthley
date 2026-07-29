package com.monthley.notification.internal;

import com.monthley.notification.api.EmailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Penghantar e-mel melalui Resend (https://resend.com).
 *
 * Kunci API dari env: MONTHLEY_RESEND_KEY (jangan letak dalam kod/yml).
 * Jika kunci tiada (dev), e-mel di-log sahaja — pembangunan tak terhalang.
 */
@Service
class ResendEmailService implements EmailPort {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);
    private static final String API = "https://api.resend.com/emails";

    private final RestClient http = RestClient.create();
    private final String apiKey;
    private final String from;

    ResendEmailService(@Value("${monthley.email.resend-key:}") String apiKey,
                       @Value("${monthley.email.from:Monthley <noreply@monthley.my>}") String from) {
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public void sendVerification(String to, String name, String verifyUrl) {
        send(to, "Sahkan e-mel anda — Monthley", EmailTemplates.verification(name, verifyUrl));
    }

    @Override
    public void sendPasswordReset(String to, String name, String resetUrl) {
        send(to, "Reset kata laluan — Monthley", EmailTemplates.passwordReset(name, resetUrl));
    }

    @Override
    public void sendWelcome(String to, String name, String portalUrl) {
        send(to, "Selamat datang ke Monthley 🎉", EmailTemplates.welcome(name, portalUrl));
    }

    @Override
    public void sendInvitation(String to, String spName, String registerUrl) {
        send(to, "Jemputan ke Monthley — " + spName, EmailTemplates.invitation(spName, registerUrl));
    }

    @Override
    public void sendReceipt(String to, String name, String spName,
                            String receiptNo, String amount, String tarikh,
                            String receiptUrl) {
        send(to, "Resit " + receiptNo + " — " + spName,
                EmailTemplates.receipt(name, spName, receiptNo, amount,
                        tarikh, receiptUrl));
    }

    private void send(String to, String subject, String html) {
        send(to, subject, html, null, null);
    }

    private void send(String to, String subject, String html,
                      byte[] lampiran, String namaFail) {
        if (apiKey == null || apiKey.isBlank()) {
            // Mod dev — tiada kunci. Log sahaja supaya pembangunan boleh diteruskan.
            log.warn(">>> [E-MEL DEV] kepada={} subjek={}", to, subject);
            if (lampiran != null) {
                log.warn(">>> lampiran: {} ({} KB)", namaFail, lampiran.length / 1024);
            }
            log.warn(">>> Tetapkan MONTHLEY_RESEND_KEY untuk menghantar sebenar.");
            logLinks(html);
            return;
        }
        try {
            http.post().uri(API)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(muatan(to, subject, html, lampiran, namaFail))
                    .retrieve()
                    .toBodilessEntity();
            log.info("E-mel dihantar: {} → {}", subject, to);
        } catch (Exception e) {
            // Jangan gagalkan pendaftaran hanya kerana e-mel gagal
            log.error("Gagal hantar e-mel kepada {}: {}", to, e.getMessage());
            logLinks(html);
        }
    }

    /**
     * Muatan Resend. Lampiran ialah base64 dalam medan 'content'.
     *
     * Map.of tidak boleh digunakan apabila lampiran hadir kerana bilangan
     * kunci berbeza; HashMap dibina secara berperingkat.
     */
    private Map<String, Object> muatan(String to, String subject, String html,
                                       byte[] lampiran, String namaFail) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("from", from);
        body.put("to", List.of(to));
        body.put("subject", subject);
        body.put("html", html);
        if (lampiran != null && lampiran.length > 0) {
            body.put("attachments", List.of(Map.of(
                    "filename", namaFail == null ? "lampiran.pdf" : namaFail,
                    "content", java.util.Base64.getEncoder().encodeToString(lampiran))));
        }
        return body;
    }

    /** Papar pautan dalam log supaya boleh diuji tanpa e-mel sebenar. */
    private void logLinks(String html) {
        var m = java.util.regex.Pattern.compile("href=\"(http[^\"]+)\"").matcher(html);
        while (m.find()) {
            log.warn(">>> PAUTAN: {}", m.group(1));
        }
    }
}
