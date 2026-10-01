plugins { kotlin("plugin.spring") }
dependencies {
    implementation(project(":application"))
    implementation("org.springframework:spring-context")
    implementation("org.apache.poi:poi-ooxml:5.4.1")
}
