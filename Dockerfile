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
EXPOSE 8080
ENTRYPOINT ["/app/wait-for-db.sh"]
