package com.ttokttok.domain.device

import com.ttokttok.domain.common.UserId

enum class AppFlavor { TEACHER, PARENT }
enum class Platform { IOS, ANDROID }

data class DeviceToken(
    val userId: UserId,
    val flavor: AppFlavor,
    val platform: Platform,
    val token: String,
)
