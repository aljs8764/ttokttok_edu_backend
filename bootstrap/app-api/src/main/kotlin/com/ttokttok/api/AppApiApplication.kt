package com.ttokttok.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** app-api: web(REST·STOMP) + realtime 어댑터를 조립한 API 서버 */
@SpringBootApplication(scanBasePackages = ["com.ttokttok"])
class AppApiApplication

fun main(args: Array<String>) {
    runApplication<AppApiApplication>(*args)
}
