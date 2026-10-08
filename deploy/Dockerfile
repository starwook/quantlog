# 서버(/opt/quantlog)에서 빌드된다. gateway.jar / app.jar 는 deploy.sh 가 미리 올려둔다. (jar는 CPU 아키텍처 무관 → ARM 서버에서도 그대로 동작)
# 컴포즈가 서비스마다 JAR 빌드 인자를 넘긴다.
FROM eclipse-temurin:17-jre
ARG JAR=app.jar
WORKDIR /app
COPY ${JAR} app.jar
# 메모리 비율은 서비스마다 컴포즈의 JAVA_OPTS 로 정한다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
