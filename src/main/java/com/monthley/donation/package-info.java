/**
 * Modul Sumbangan — kutipan derma (ADR 0020).
 *
 * Derma berbeza daripada setiap aliran wang lain dalam sistem: penderma
 * ialah orang luar tanpa akaun, tiada invois untuk dijelaskan, dan bayaran
 * datang melalui pautan awam tanpa log masuk.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Sumbangan",
        allowedDependencies = { "shared", "document :: api", "payment :: api",
                                "ledger :: api", "notification :: api",
                                "gateway :: api", "storage :: api" })
package com.monthley.donation;
