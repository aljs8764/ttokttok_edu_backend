package com.ttokttok.api

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/** 헥사고날 의존 규칙 (스펙 2-1). 위반 시 CI 실패. */
class ArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.ttokttok")

    @Test
    fun `domain 은 프레임워크를 모른다`() {
        noClasses().that().resideInAPackage("com.ttokttok.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.persistence..", "org.hibernate..", "com.fasterxml..",
            )
            .check(classes)
    }

    @Test
    fun `domain 은 바깥 계층에 의존하지 않는다`() {
        noClasses().that().resideInAPackage("com.ttokttok.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ttokttok.application..", "com.ttokttok.adapter..")
            .check(classes)
    }

    @Test
    fun `application 은 adapter 를 참조하지 않고 JPA·Web 을 모른다`() {
        noClasses().that().resideInAPackage("com.ttokttok.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.ttokttok.adapter..", "jakarta.persistence..", "org.springframework.web..", "org.springframework.data..",
            )
            .check(classes)
    }

    @Test
    fun `어댑터끼리는 서로 참조하지 않는다`() {
        val adapters = listOf("in.web", "in.scheduler", "out.persistence", "out.notification", "out.realtime")
        adapters.forEach { me ->
            val others = adapters.filter { it != me }.map { "com.ttokttok.adapter.$it.." }.toTypedArray()
            noClasses().that().resideInAPackage("com.ttokttok.adapter.$me..")
                .should().dependOnClassesThat().resideInAnyPackage(*others)
                .check(classes)
        }
    }

    @Test
    fun `인바운드 어댑터는 서비스 구현이 아닌 Inbound Port 에만 의존한다`() {
        noClasses().that().resideInAnyPackage("com.ttokttok.adapter.in..")
            .should().dependOnClassesThat().resideInAnyPackage("com.ttokttok.application.service..")
            .check(classes)
    }
}
