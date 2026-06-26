# 멀티스테이지: JDK21 로 bootJar 빌드 → JRE21 런타임. 무인자=서버 모드, --adc.cli.export-domain= 인자=CLI 모드(doc/31 C1).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# 테스트는 Testcontainers(podman) 의존 → 이미지 빌드에선 제외(빌드/배포는 별도 CI·로컬에서 검증)
RUN ./gradlew --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
# bootJar 만 복사(*-SNAPSHOT.jar; plain jar 는 *-SNAPSHOT-plain.jar 라 미매칭)
COPY --from=build /src/build/libs/*-SNAPSHOT.jar /app/app.jar
# DB 준비 대기 래퍼 — 컨테이너 동시 기동 시 크래시 루프 방지(doc/32). "$@" 로 CLI 인자 그대로 전달.
COPY wait-for-db.sh /app/wait-for-db.sh
RUN chmod +x /app/wait-for-db.sh
# 이미지는 항상 컨테이너 배포 컨텍스트 → container 프로파일(PG·Loki LAN) 기본값.
# 서버 pod(adc.yaml)도 동일 값 명시(중복 무해), one-off CLI(`-domain -ls` 등)도 env 없이 PG 접속(빈 H2 회피).
# 로컬·테스트는 소스(gradle)로 실행하므로 application.yml(H2) 유지(이 ENV 무영향). 다른 프로파일은 -e 로 override.
ENV SPRING_PROFILES_ACTIVE=container
EXPOSE 8080
ENTRYPOINT ["/app/wait-for-db.sh"]
