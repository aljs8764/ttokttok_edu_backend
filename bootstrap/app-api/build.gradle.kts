plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
}
dependencies {
    implementation(project(":application"))
    implementation(project(":adapter-in-web"))
    implementation(project(":adapter-out-persistence"))
    implementation(project(":adapter-out-storage"))
    implementation(project(":adapter-out-realtime"))
    implementation(project(":adapter-out-notification"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // 통합 테스트에서 Core Loop 전체(발송 워커 포함)를 한 번에 검증
    testImplementation(project(":adapter-in-scheduler"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.0")
}
