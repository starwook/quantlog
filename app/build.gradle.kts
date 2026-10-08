plugins {
    id("org.springframework.boot")
}

// 게이트웨이와 코드를 공유하지 않는다(의존 없음). 게이트웨이와는 HTTP·웹소켓·DB 테이블 계약으로만 만난다 — docs/서버-분리.md.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 배포 스크립트(deploy/deploy.sh)가 루트 build/libs 에서 jar 를 가져간다.
tasks.bootJar {
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    archiveFileName.set("app.jar")
}

// 정상 jar(-plain)는 필요 없다.
tasks.jar {
    enabled = false
}
