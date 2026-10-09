plugins { kotlin("plugin.spring") }
dependencies {
    implementation(project(":application"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json") // AUTH-006 소셜 사용자 정보 응답 파싱
    implementation("com.google.firebase:firebase-admin:9.4.3")
    implementation(platform("software.amazon.awssdk:bom:2.31.50"))
    implementation("software.amazon.awssdk:sesv2")
}
