/**
 * Storan objek — fail yang dimuat naik pengguna.
 *
 * Dua baldi dengan makna berbeza:
 *
 *   AWAM     Poster kempen derma. Dilihat oleh sesiapa yang membuka
 *            pautan awam, jadi dihidangkan terus oleh Nginx tanpa
 *            pengesahan.
 *
 *   PERIBADI Gambar aduan dan lampiran. Memerlukan URL bertandatangan
 *            yang luput — pemilik fail bukan orang awam.
 *
 * MinIO pada VPS, bercakap protokol S3. Beralih ke Cloudflare R2 atau S3
 * sebenar kemudian menukar endpoint dan kunci, bukan kod.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Storage")
package com.monthley.storage;
