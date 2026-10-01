// 유스케이스 + 포트. 허용 의존: domain, spring-context(애노테이션), spring-tx(트랜잭션 경계)
dependencies {
    api(project(":domain"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.slf4j:slf4j-api")
}
