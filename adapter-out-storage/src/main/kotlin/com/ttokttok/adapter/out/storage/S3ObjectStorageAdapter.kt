package com.ttokttok.adapter.out.storage

import com.ttokttok.application.port.out.ObjectStoragePort
import com.ttokttok.application.port.out.PresignedUrl
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * S3 서명 URL 어댑터 (스펙 8장: 다운로드 5분 만료).
 * 로컬은 docker compose 의 localstack — ttok.storage.endpoint 를 주면 path-style 로 붙는다.
 * 자격 증명은 기본 체인(운영: ECS 태스크 역할, 로컬: AWS_ACCESS_KEY_ID 등 환경 변수).
 */
@Component
class S3ObjectStorageAdapter(
    @Value("\${ttok.storage.bucket}") private val bucket: String,
    @Value("\${ttok.storage.region:ap-northeast-2}") region: String,
    @Value("\${ttok.storage.endpoint:}") endpoint: String,
    @Value("\${ttok.storage.upload-ttl:PT10M}") private val uploadTtl: Duration,
    @Value("\${ttok.storage.download-ttl:PT5M}") private val downloadTtl: Duration,
) : ObjectStoragePort, DisposableBean {

    private val s3Config = S3Configuration.builder().pathStyleAccessEnabled(endpoint.isNotBlank()).build()
    private val client: S3Client = S3Client.builder()
        .region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.create())
        .serviceConfiguration(s3Config)
        .apply { if (endpoint.isNotBlank()) endpointOverride(URI.create(endpoint)) }
        .build()
    private val presigner: S3Presigner = S3Presigner.builder()
        .region(Region.of(region)).credentialsProvider(DefaultCredentialsProvider.create())
        .serviceConfiguration(s3Config)
        .apply { if (endpoint.isNotBlank()) endpointOverride(URI.create(endpoint)) }
        .build()

    override fun presignUpload(key: String, mime: String, size: Long): PresignedUrl {
        val put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime).contentLength(size).build()
        val signed = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(uploadTtl).putObjectRequest(put).build())
        return PresignedUrl(
            url = signed.url().toString(), method = "PUT",
            // 서명에 묶인 헤더 — 클라이언트가 그대로 보내야 한다
            headers = signed.signedHeaders().filterKeys { !it.equals("host", ignoreCase = true) }.mapValues { it.value.joinToString(",") },
            expiresAt = signed.expiration(),
        )
    }

    override fun presignDownload(key: String, filename: String, mime: String): PresignedUrl {
        val encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
        val get = GetObjectRequest.builder().bucket(bucket).key(key)
            .responseContentType(mime)
            .responseContentDisposition("inline; filename*=UTF-8''$encoded")
            .build()
        val signed = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(downloadTtl).getObjectRequest(get).build())
        return PresignedUrl(signed.url().toString(), "GET", emptyMap(), signed.expiration() ?: Instant.now().plus(downloadTtl))
    }

    override fun sizeOf(key: String): Long? = try {
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength()
    } catch (e: NoSuchKeyException) {
        null
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) null else throw e
    }

    override fun delete(key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()) // S3 는 없는 키 삭제도 성공
    }

    override fun destroy() {
        presigner.close()
        client.close()
    }
}
