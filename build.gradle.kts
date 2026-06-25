plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.pentasecurity"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // YAML (OpenAPI / 설정 보조)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // 스펙 파싱 (03 문서)
    implementation("io.swagger.parser.v3:swagger-parser:2.1.22") // OpenAPI 2.0/3.x
    implementation("com.univocity:univocity-parsers:2.9.1")      // CSV

    // 근사 자료구조 (02/04 문서): HLL distinct + KLL 분위수
    implementation("org.apache.datasketches:datasketches-java:6.1.1")

    // DB
    runtimeOnly("com.h2database:h2")            // 개발/테스트
    runtimeOnly("org.postgresql:postgresql")    // 운영(사내 표준 RDB)

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Testcontainers(PG 통합 테스트, doc/28). 버전은 Spring Boot BOM 이 관리(미명시).
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 실 Loki 통합 테스트 가드용 -Dloki.live 전달 (평소 build 에는 영향 없음)
    System.getProperty("loki.live")?.let { systemProperty("loki.live", it) }
    testLogging { showStandardStreams = true }
    // rootless podman 소켓 자동 연결(doc/28 §2). uid 하드코딩 없이 XDG_RUNTIME_DIR 파생, 가드:
    // DOCKER_HOST 기설정·소켓 부재 시 미개입 → 실docker/無docker 환경 무영향(빌드 무회귀).
    if (System.getenv("DOCKER_HOST") == null) {
        val xdg = System.getenv("XDG_RUNTIME_DIR")
        if (xdg != null && file("$xdg/podman/podman.sock").exists()) {
            environment("DOCKER_HOST", "unix://$xdg/podman/podman.sock")
            environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        }
    }
}
