package com.ttokttok.domain.file

import com.ttokttok.domain.common.ConflictException
import com.ttokttok.domain.common.InstitutionId
import com.ttokttok.domain.common.InvalidInputException
import com.ttokttok.domain.common.UserId
import com.ttokttok.domain.common.Uuid7
import java.time.Instant
import java.util.UUID

@JvmInline value class FileId(val value: UUID) { companion object { fun new() = FileId(Uuid7.next()) } }

/** 업로드 용도 — 용도별로 허용 형식이 다르다 */
enum class FilePurpose(val allowedMimes: Set<String>) {
    LOGO(setOf("image/jpeg", "image/png")),
    SEAL(setOf("image/jpeg", "image/png")),
    ABSENCE_EVIDENCE(setOf("image/jpeg", "image/png", "image/heic", "application/pdf")),
    NOTICE_ATTACHMENT(setOf("image/jpeg", "image/png", "image/heic", "application/pdf")),
}

enum class FileStatus { PENDING, UPLOADED }

/**
 * S3 업로드 파일 (스펙 8장: 타입 화이트리스트 jpg/png/heic/pdf, 20MB, 다운로드는 5분 만료 Signed URL).
 * 흐름: presign(PENDING) → 클라이언트가 S3 로 직접 PUT → complete 로 존재·크기 확인(UPLOADED) → 다른 기능에서 참조
 */
data class StoredFile(
    val id: FileId,
    val institutionId: InstitutionId,
    val uploaderId: UserId,
    val purpose: FilePurpose,
    val originalName: String,
    val mime: String,
    val size: Long,
    val storageKey: String,
    val status: FileStatus = FileStatus.PENDING,
    val createdAt: Instant,
) {
    fun complete(actualSize: Long): StoredFile {
        if (status == FileStatus.UPLOADED) return this
        if (actualSize != size) throw ConflictException("SIZE_MISMATCH", "업로드된 파일 크기가 신고한 크기와 다릅니다")
        return copy(status = FileStatus.UPLOADED)
    }

    /** 다른 기능에서 참조하기 전 확인: 같은 기관·업로드 완료·용도 일치 */
    fun requireUsableFor(institutionId: InstitutionId, purpose: FilePurpose) {
        if (this.institutionId != institutionId) throw InvalidInputException("INVALID_FILE", "다른 기관의 파일입니다")
        if (status != FileStatus.UPLOADED) throw InvalidInputException("FILE_NOT_UPLOADED", "업로드가 완료되지 않은 파일입니다")
        if (this.purpose != purpose) throw InvalidInputException("INVALID_FILE", "용도가 다른 파일입니다 (${this.purpose})")
    }

    companion object {
        const val MAX_SIZE = 20L * 1024 * 1024

        fun request(
            institutionId: InstitutionId, uploader: UserId, purpose: FilePurpose,
            originalName: String, mime: String, size: Long, now: Instant,
        ): StoredFile {
            val m = mime.lowercase().trim()
            if (m !in purpose.allowedMimes) throw InvalidInputException("INVALID_FILE_TYPE", "허용되지 않는 파일 형식입니다 ($mime)")
            if (size !in 1..MAX_SIZE) throw InvalidInputException("FILE_TOO_LARGE", "파일은 20MB 이하여야 합니다")
            val name = originalName.trim().ifEmpty { "file" }.take(200)
            val id = FileId.new()
            // 원본 이름은 메타데이터로만 두고, 키에는 안전한 문자만
            val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
            val key = "inst/${institutionId.value}/${purpose.name.lowercase()}/${id.value}" + if (ext.isEmpty()) "" else ".$ext"
            return StoredFile(id, institutionId, uploader, purpose, name, m, size, key, FileStatus.PENDING, now)
        }
    }
}
