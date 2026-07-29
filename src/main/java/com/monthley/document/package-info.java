/**
 * Document — invois, resit, penomboran thread-safe, idempotency.
 *
 * Modul ini memiliki DATA dokumen. Ia tidak tahu bagaimana dokumen
 * dirender atau dihantar — itu kerja lapisan di atasnya.
 *
 * Percubaan pertama meletakkan 'Resend Document' di sini dan menambah
 * notification::api dan statement::api. ModularityTests menolaknya:
 * statement sudah bergantung pada document::api untuk pautan awam, jadi
 * itu KITARAN. Resend berpindah ke statement, yang sudah memegang
 * kedua-dua belah.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Financial Document",
        allowedDependencies = { "shared" })
package com.monthley.document;
