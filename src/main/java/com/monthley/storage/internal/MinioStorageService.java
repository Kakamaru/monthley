package com.monthley.storage.internal;

import com.monthley.storage.api.StoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/**
 * Storan melalui MinIO.
 *
 * MinIO mendengar pada 127.0.0.1 sahaja — ia tidak terdedah ke internet.
 * Fail awam dicapai melalui Nginx di /files/, yang memproksi ke baldi
 * awam.
 */
@Service
class MinioStorageService implements StoragePort {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String publicBaseUrl;

    // Klien dicipta MALAS, pada penggunaan pertama.
    //
    // SDK melempar 'Secret access key cannot be blank' semasa dibina, dan
    // pembangunan tempatan tiada kunci MinIO. Membinanya dalam constructor
    // bermakna SELURUH aplikasi gagal bermula — 383 ujian merah kerana
    // storan tidak dikonfigurasi.
    //
    // Malas bermakna kegagalan berlaku pada muat naik pertama, di mana ia
    // boleh dilaporkan kepada pengguna dan bukan menghalang boot.
    private volatile S3Client s3;
    private volatile S3Presigner presigner;

    MinioStorageService(
            @Value("${monthley.storage.endpoint:http://127.0.0.1:9000}") String endpoint,
            @Value("${monthley.storage.access-key:monthley}") String accessKey,
            @Value("${monthley.storage.secret-key:}") String secretKey,
            @Value("${monthley.app-url}") String appUrl) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.publicBaseUrl = appUrl.replaceAll("/$", "") + "/files";
    }

    private void pastikanKlien() {
        if (s3 != null) return;

        synchronized (this) {
            if (s3 != null) return;

            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException(
                        "Storan fail tidak dikonfigurasi. "
                        + "Tetapkan MONTHLEY_STORAGE_SECRET.");
            }

            var creds = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));

            // pathStyleAccess: MinIO menggunakan /baldi/kunci, bukan
            // baldi.host/kunci seperti S3 sebenar. Tanpa ini, SDK cuba
            // menyelesaikan 'monthley-public.127.0.0.1' yang tidak wujud.
            var config = S3Configuration.builder().pathStyleAccessEnabled(true).build();

            this.presigner = S3Presigner.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(creds)
                    .region(Region.US_EAST_1)
                    .serviceConfiguration(config)
                    .build();

            this.s3 = S3Client.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(creds)
                    .region(Region.US_EAST_1)   // MinIO abaikan, SDK menuntutnya
                    .serviceConfiguration(config)
                    .build();
        }
    }

    @Override
    public String put(String bucket, String key, InputStream data,
                      long size, String contentType) {
        pastikanKlien();
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromInputStream(data, size));

        log.info("Fail dimuat naik: {}/{} ({} bait)", bucket, key, size);
        return key;
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }

    /**
     * URL bertandatangan menunjuk terus ke MinIO, yang TIDAK terdedah ke
     * internet — jadi ia hanya berfungsi dari dalam pelayan.
     *
     * Fail peribadi mesti dihidangkan melalui endpoint backend yang
     * menyemak kebenaran dan menstrimnya. Kaedah ini wujud untuk kegunaan
     * dalaman dan sebagai laluan sedia apabila storan berpindah ke awan.
     */
    @Override
    public String signedUrl(String key, Duration tempoh) {
        pastikanKlien();
        var req = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(tempoh)
                .getObjectRequest(b -> b.bucket(PRIVATE).key(key))
                .build());
        return req.url().toString();
    }

    @Override
    public void delete(String bucket, String key) {
        pastikanKlien();
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(key).build());
        log.info("Fail dibuang: {}/{}", bucket, key);
    }
}
