plugins { kotlin("plugin.spring") }
dependencies {
    implementation(project(":application"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.3.1")
}
