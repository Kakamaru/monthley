package com.monthley.shared;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ModuleGuard — hak modul per SP (ADR 0016).
 *
 * Yang diuji bukan sekadar 'ada hak lulus, tiada hak tolak', tetapi tiga
 * kes yang mudah tersalah dan senyap:
 *
 *   1. Superadmin TIDAK melepasi guard. Berbeza daripada Access.hasRole,
 *      yang memulangkan true untuk superadmin.
 *   2. Hak SP lain tidak memberi akses kepada SP semasa.
 *   3. Hak yang sudah TAMAT tidak lagi memberi akses.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModuleGuardTest {

    @Autowired ModuleGuard guard;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPG1', 'Ada Modul', 'ACTIVE', NOW(), NOW(), 0),
                   ('SPG2', 'Tiada Modul', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT IGNORE INTO ref_module (code, name, sort_order, status, created_at, updated_at, version)
            VALUES ('UJIAN_MOD', 'Modul Ujian', 1, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();

        // SPG1 sahaja yang ada hak.
        em.createNativeQuery("""
            INSERT INTO sp_module (sp_code, module_code, status, start_date,
                                   created_at, updated_at, version)
            VALUES ('SPG1', 'UJIAN_MOD', 'ACTIVE', CURDATE(), NOW(), NOW(), 0)
            """).executeUpdate();
        em.flush();
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private void masukSebagai(String sp, String... authorities) {
        TenantContext.set(sp);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "n/a",
                        java.util.Arrays.stream(authorities)
                                .map(SimpleGrantedAuthority::new).map(a -> (org.springframework.security.core.GrantedAuthority) a)
                                .toList()));
    }

    @Test
    @DisplayName("SP yang melanggan boleh guna modul")
    void spAdaHak() {
        masukSebagai("SPG1", "SP_SPG1_SP_ADMIN");
        assertThat(guard.has("UJIAN_MOD")).isTrue();
        guard.require("UJIAN_MOD", "mencipta rekod");   // tidak campak
    }

    @Test
    @DisplayName("SP yang tidak melanggan ditolak dengan mesej boleh dibaca")
    void spTiadaHak() {
        masukSebagai("SPG2", "SP_SPG2_SP_ADMIN");
        assertThat(guard.has("UJIAN_MOD")).isFalse();
        assertThatThrownBy(() -> guard.require("UJIAN_MOD", "mencipta rekod"))
                .isInstanceOf(Access.AccessDeniedException.class)
                .hasMessageContaining("belum dilanggan");
    }

    @Test
    @DisplayName("superadmin TIDAK melepasi guard — berbeza daripada Access.hasRole")
    void superadminTidakLepas() {
        masukSebagai("SPG2", "ROLE_SUPERADMIN");

        // Access memberi laluan kepada superadmin...
        assertThat(Access.hasRole("SP_ADMIN")).isTrue();

        // ...tetapi ModuleGuard tidak. Hak ialah tentang SP, bukan pengguna.
        assertThat(guard.has("UJIAN_MOD")).isFalse();
        assertThatThrownBy(() -> guard.require("UJIAN_MOD", "mencipta rekod"))
                .isInstanceOf(Access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("hak yang sudah tamat tidak memberi akses")
    void hakTamat() {
        em.createNativeQuery("""
            UPDATE sp_module SET status='ENDED', end_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY)
            WHERE sp_code='SPG1' AND module_code='UJIAN_MOD'
            """).executeUpdate();
        em.flush();

        masukSebagai("SPG1", "SP_SPG1_SP_ADMIN");
        assertThat(guard.has("UJIAN_MOD")).isFalse();
    }

    @Test
    @DisplayName("tiada tenant — tiada akses")
    void tiadaTenant() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "n/a", List.of()));
        assertThat(guard.has("UJIAN_MOD")).isFalse();
    }
}
