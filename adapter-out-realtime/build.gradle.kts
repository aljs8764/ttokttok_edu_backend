plugins { kotlin("plugin.spring") }
dependencies {
    implementation(project(":application"))
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework:spring-tx")
}
