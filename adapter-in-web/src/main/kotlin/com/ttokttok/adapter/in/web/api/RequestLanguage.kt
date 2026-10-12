package com.ttokttok.adapter.`in`.web.api

import jakarta.servlet.http.HttpServletRequest
import java.util.Locale

/**
 * SET-007 응답 언어. 클라이언트가 고른 화면 언어를 Accept-Language 로 보낸다(웹 BFF·앱 Dio).
 * 첫 번째(가중치가 가장 높은) 언어가 en 이면 영어, 그 밖(헤더 없음 포함)은 한국어.
 * 서버 기본 로캘(JVM)에 기대지 않으려고 Spring LocaleResolver 대신 헤더를 직접 읽는다.
 */
object RequestLanguage {
    fun isEnglish(request: HttpServletRequest?): Boolean {
        val header = request?.getHeader("Accept-Language")?.takeIf { it.isNotBlank() } ?: return false
        val first = runCatching { Locale.LanguageRange.parse(header).firstOrNull() }.getOrNull() ?: return false
        return first.range.lowercase().startsWith("en")
    }
}
