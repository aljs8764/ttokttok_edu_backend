plugins { kotlin("plugin.spring") }
dependencies {
    implementation(project(":application"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.google.firebase:firebase-admin:9.4.3")
    implementation(platform("software.amazon.awssdk:bom:2.31.50"))
    implementation("software.amazon.awssdk:sesv2")
}
