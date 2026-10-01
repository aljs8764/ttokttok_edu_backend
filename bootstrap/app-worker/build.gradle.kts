plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
}
dependencies {
    implementation(project(":application"))
    implementation(project(":adapter-in-scheduler"))
    implementation(project(":adapter-out-persistence"))
    implementation(project(":adapter-out-storage"))
    implementation(project(":adapter-out-notification"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
