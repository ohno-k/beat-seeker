package com.beatseeker.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

/**
 * 【クラスの役割】 リザルト画像を Cloudflare R2（S3 互換オブジェクトストレージ）に読み書きするサービス。
 *
 * 設計方針:
 *  - 画像バイト列は DB に持たず R2 に置く。DB（{@code result_images}）にはオブジェクトキー＋メタ情報のみ保存し、
 *    DB の肥大化を防ぐ。
 *  - バケットは非公開運用。閲覧時は本サービスが短時間有効の「署名付き GET URL」を発行し、
 *    所有者（JWT 認証済みユーザー）だけが画像 URL を取得できるようにする。
 *  - R2 は S3 互換なので AWS SDK v2 をそのまま利用し、エンドポイントだけ R2 のものに差し替える。
 *
 * 未設定時の挙動:
 *  - {@code storage.r2.*} が未設定（endpoint/access-key/secret-key/bucket のいずれかが空）の場合、
 *    各操作は 503 SERVICE_UNAVAILABLE を投げる。これにより R2 未設定でもアプリ起動・他機能には影響しない。
 *
 * クライアント（{@link S3Client} / {@link S3Presigner}）は初回利用時に遅延生成してキャッシュする
 * （未設定環境で起動時に壊れないようにするため）。
 */
@Service
public class R2StorageService {

    /** R2 の S3 互換エンドポイント。例: {@code https://<ACCOUNT_ID>.r2.cloudflarestorage.com} */
    @Value("${storage.r2.endpoint:}")
    private String endpoint;
    /** R2 API トークンのアクセスキー ID。 */
    @Value("${storage.r2.access-key:}")
    private String accessKey;
    /** R2 API トークンのシークレットアクセスキー。 */
    @Value("${storage.r2.secret-key:}")
    private String secretKey;
    /** リザルト画像を格納するバケット名。 */
    @Value("${storage.r2.bucket:}")
    private String bucket;
    /** 署名用リージョン。R2 は "auto"、S3 を使う場合は us-east-1 等の実リージョン。 */
    @Value("${storage.r2.region:auto}")
    private String region;
    /** 署名付き GET URL の有効秒数。 */
    @Value("${storage.r2.presign-expiry-seconds:3600}")
    private long presignExpirySeconds;

    /** 遅延生成される S3 同期クライアント（PUT / DELETE 用）。 */
    private volatile S3Client s3Client;
    /** 遅延生成される署名 URL 発行器（GET 用）。 */
    private volatile S3Presigner s3Presigner;

    /** R2 接続に必要な設定がすべて揃っているか。 */
    public boolean isConfigured() {
        return notBlank(endpoint) && notBlank(accessKey) && notBlank(secretKey) && notBlank(bucket);
    }

    /** 未設定なら 503 を投げる。設定済みなら何もしない。 */
    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "リザルト画像ストレージ (R2) が未設定です。R2_ENDPOINT / R2_ACCESS_KEY / R2_SECRET_KEY / R2_BUCKET を設定してください。");
        }
    }

    /** PUT / DELETE 用の S3 クライアントを取得（初回のみ生成）。 */
    private S3Client s3() {
        S3Client local = s3Client;
        if (local == null) {
            synchronized (this) {
                local = s3Client;
                if (local == null) {
                    local = S3Client.builder()
                            .endpointOverride(URI.create(endpoint))
                            .region(Region.of(region))
                            .credentialsProvider(staticCredentials())
                            // カスタムエンドポイントでは仮想ホスト形式より path-style が安全。
                            .serviceConfiguration(S3Configuration.builder()
                                    .pathStyleAccessEnabled(true)
                                    .build())
                            .build();
                    s3Client = local;
                }
            }
        }
        return local;
    }

    /** GET 署名 URL 発行用の presigner を取得（初回のみ生成）。 */
    private S3Presigner presigner() {
        S3Presigner local = s3Presigner;
        if (local == null) {
            synchronized (this) {
                local = s3Presigner;
                if (local == null) {
                    local = S3Presigner.builder()
                            .endpointOverride(URI.create(endpoint))
                            .region(Region.of(region))
                            .credentialsProvider(staticCredentials())
                            .serviceConfiguration(S3Configuration.builder()
                                    .pathStyleAccessEnabled(true)
                                    .build())
                            .build();
                    s3Presigner = local;
                }
            }
        }
        return local;
    }

    private StaticCredentialsProvider staticCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    /**
     * 画像バイト列を R2 にアップロードする。
     *
     * @param key         オブジェクトキー（例: {@code results/<userId>/<uuid>.webp}）
     * @param data        画像のバイト列（クライアントで圧縮済みを想定）
     * @param contentType MIME タイプ（image/webp など）
     */
    public void upload(String key, byte[] data, String contentType) {
        ensureConfigured();
        s3().putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(data));
    }

    /**
     * 指定オブジェクトの、短時間有効な署名付き GET URL を発行する。
     * この URL は {@code <img src>} で直接表示でき、Authorization ヘッダを必要としない。
     */
    public String presignGet(String key) {
        ensureConfigured();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(presignExpirySeconds))
                .getObjectRequest(getObjectRequest)
                .build();
        return presigner().presignGetObject(presignRequest).url().toString();
    }

    /** オブジェクトを削除する。 */
    public void delete(String key) {
        ensureConfigured();
        s3().deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
