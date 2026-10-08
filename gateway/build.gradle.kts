plugins {
    id("org.springframework.boot")
}

// 앱과 코드를 공유하지 않는다(의존 없음). 계약은 docs/contracts/ 의 형식 파일과 계약 테스트로 맞춘다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
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
    archiveFileName.set("gateway.jar")
}

tasks.jar {
    enabled = false
}
