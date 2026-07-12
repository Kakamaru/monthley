package com.monthley;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforce sempadan modul.
 *
 * Ujian ini GAGAL jika mana-mana modul melanggar allowedDependencies —
 * contohnya jika billing cuba menulis terus ke journal_line tanpa LedgerPort.
 *
 * Inilah yang menjadikan peraturan "satu pintu ke ledger" bukan sekadar
 * niat baik dalam dokumen, tetapi dikuatkuasakan oleh build.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(MonthleyApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writeDocumentationSnippets() {
        new Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
