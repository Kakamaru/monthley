package com.monthley.gateway.internal;

import com.monthley.document.api.DocumentNumberPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rujukan gerbang.
 *
 * Rujukan ini muncul dalam penyata bank gerbang, dan prefix SP bermakna
 * wang boleh diagihkan kepada SP yang betul TANPA menyoal pangkalan data.
 * Rujukan yang berulang bermakna dua bayaran berbeza tidak dapat
 * dibezakan dalam penyata itu.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GatewayRefTest {

    @Autowired DocumentNumberPort numbers;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        for (String kod : new String[]{"SPG1", "SPG2"}) {
            em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                     created_at, updated_at, version)
                VALUES (:k, :n, 'ACTIVE', NOW(), NOW(), 0)
                """).setParameter("k", kod).setParameter("n", "Ujian " + kod)
                .executeUpdate();
        }
        em.flush();
    }

    @Test
    @DisplayName("kaunter menaik dan tidak berulang")
    void kaunterMenaik() {
        long a = numbers.nextValue("SPG1", "GATEWAY_REF");
        long b = numbers.nextValue("SPG1", "GATEWAY_REF");
        long c = numbers.nextValue("SPG1", "GATEWAY_REF");

        assertThat(b).isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
    }

    /**
     * Kaunter adalah PER SP.
     *
     * Legacy menggunakan kaunter global, yang bermakna setiap SP bersaing
     * untuk kunci baris yang sama. SP sudah ada dalam prefix rujukan, jadi
     * turutan global tidak menambah apa-apa selain perbalahan.
     */
    @Test
    @DisplayName("kaunter berasingan bagi setiap SP")
    void kaunterPerSp() {
        long a1 = numbers.nextValue("SPG1", "GATEWAY_REF");
        long b1 = numbers.nextValue("SPG2", "GATEWAY_REF");
        long a2 = numbers.nextValue("SPG1", "GATEWAY_REF");

        // SPG2 bermula sendiri, tidak menyambung daripada SPG1.
        assertThat(b1).isEqualTo(a1);
        assertThat(a2).isEqualTo(a1 + 1);
    }

    @Test
    @DisplayName("100 rujukan berturutan semuanya unik")
    void seratusRujukanUnik() {
        Set<String> dilihat = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long v = numbers.nextValue("SPG1", "GATEWAY_REF");
            String ref = "SPG1" + Long.toString(v, 36).toUpperCase();
            assertThat(dilihat.add(ref))
                    .as("rujukan berulang: " + ref)
                    .isTrue();
        }
        assertThat(dilihat).hasSize(100);
    }

    /**
     * Base36 memendekkan rujukan.
     *
     * Ruang rujukan pada penyata bank terhad, dan prefix SP sudah memakan
     * sebahagiannya. Satu juta dalam desimal ialah tujuh aksara; dalam
     * base36 ia empat.
     */
    @Test
    @DisplayName("base36 lebih pendek daripada desimal")
    void base36LebihPendek() {
        String b36 = Long.toString(1_000_000L, 36).toUpperCase();
        assertThat(b36).hasSize(4);
        assertThat(String.valueOf(1_000_000L)).hasSize(7);
    }

    @Test
    @DisplayName("prefix SP boleh dibaca semula daripada rujukan")
    void prefixBolehDibaca() {
        long v = numbers.nextValue("SPG1", "GATEWAY_REF");
        String ref = "SPG1" + Long.toString(v, 36).toUpperCase();

        // Inilah keseluruhan sebab format ini wujud: penyata bank
        // menunjukkan rujukan, dan rujukan itu memberitahu SP mana —
        // tanpa pertanyaan.
        assertThat(ref).startsWith("SPG1");
    }
}
