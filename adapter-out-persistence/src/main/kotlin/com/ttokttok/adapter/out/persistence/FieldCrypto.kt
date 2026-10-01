package com.ttokttok.adapter.out.persistence

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SEC-001 개인정보 암호화.
 * - encrypt/decrypt: AES-256-GCM, 저장 형식 base64(iv[12] || ciphertext+tag)
 * - hash: HMAC-SHA256 hex — 암호문으로는 검색이 안 되므로 중복·매핑 조회용
 * 운영에서는 KMS로 데이터키를 복호화해 주입한다 (TODO: KMS envelope).
 */
@Component
class FieldCrypto(
    @Value("\${ttok.crypto.encryption-key}") encryptionKeyB64: String,
    @Value("\${ttok.crypto.hash-key}") hashKeyB64: String,
) {
    private val encKey = SecretKeySpec(decodeKey(encryptionKeyB64, "encryption-key"), "AES")
    private val hashKey = SecretKeySpec(decodeKey(hashKeyB64, "hash-key"), "HmacSHA256")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(TAG_BITS, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(stored: String): String {
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(TAG_BITS, bytes, 0, IV_LEN))
        }
        return String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
    }

    fun hash(plain: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(hashKey) }
        return mac.doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun decodeKey(b64: String, name: String): ByteArray {
        val key = Base64.getDecoder().decode(b64)
        require(key.size == 32) { "ttok.crypto.$name 은 base64 인코딩된 32바이트여야 합니다" }
        return key
    }

    companion object {
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}
