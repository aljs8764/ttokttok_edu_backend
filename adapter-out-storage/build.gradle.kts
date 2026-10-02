plugins { kotlin("plugin.spring") }
dependencies {
    implementation(project(":application"))
    implementation("org.springframework:spring-context")
    implementation("org.apache.poi:poi-ooxml:5.4.1")
    implementation(platform("software.amazon.awssdk:bom:2.31.50"))
    implementation("software.amazon.awssdk:s3")
}
