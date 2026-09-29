# 서버(/opt/quantlog)에서 빌드된다. app.jar 는 deploy.sh 가 미리 올려둔다. (jar는 CPU 아키텍처 무관 → ARM 서버에서도 그대로 동작)
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
