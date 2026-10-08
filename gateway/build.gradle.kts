plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(testFixtures(project(":common")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
