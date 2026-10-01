package com.ttokttok.adapter.out.persistence

import com.ttokttok.application.port.out.ClockPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.boot.autoconfigure.domain.EntityScan
import java.time.Instant

@Configuration
@EnableJpaRepositories(basePackages = ["com.ttokttok.adapter.out.persistence.repository"])
@EntityScan(basePackages = ["com.ttokttok.adapter.out.persistence.entity"])
class PersistenceConfig {
    /** 실제 시계. 테스트는 @Primary 고정 시계 빈으로 교체한다. */
    @Bean
    fun systemClock(): ClockPort = object : ClockPort {
        override fun now(): Instant = Instant.now()
    }
}

@Configuration
class ShedLockConfig {
    @Bean
    fun lockProvider(dataSource: javax.sql.DataSource): net.javacrumbs.shedlock.core.LockProvider =
        net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider(
            net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(org.springframework.jdbc.core.JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )
}
