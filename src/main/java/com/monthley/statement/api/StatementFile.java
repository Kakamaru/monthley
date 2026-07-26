package com.monthley.statement.api;

/**
 * Penyata yang sudah dirender, dengan nama failnya.
 *
 * Nama fail dibina SEKALI di sini supaya tiga pemanggil tidak menghasilkan
 * tiga konvensyen penamaan. Ia data, bukan HTTP — setiap pengawal
 * membalutnya sendiri mengikut rangka kerjanya.
 */
public record StatementFile(String filename, String contentType, byte[] content) {
}
