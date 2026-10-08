plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common"))
    // 런타임에만 같이 뜬다. 컴파일 의존이 없어서 앱 코드가 게이트웨이 클래스를 직접 부르면 빌드가 깨진다(경계 강제).
    runtimeOnly(project(":gateway"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation(testFixtures(project(":common")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 배포 스크립트(deploy/deploy.sh, .github/workflows/deploy.yml)가 루트 build/libs/*.jar 한 개를 기대한다 — 경로를 그대로 유지한다.
tasks.bootJar {
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
}

// 정상 jar(-plain)는 필요 없다. 배포가 build/libs 에서 -plain 을 걸러내긴 하지만 아예 만들지 않는다.
tasks.jar {
    enabled = false
}
