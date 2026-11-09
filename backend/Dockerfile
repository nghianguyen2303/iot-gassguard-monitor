# ---------------------------------------------------------------------------
# Image backend Spring Boot (Java 21) - build nhiều tầng:
#   1) maven: tải thư viện + đóng gói JAR (bỏ qua test; test chạy riêng ở bước 14)
#   2) JRE alpine nhỏ gọn, chạy bằng user không phải root
# Không có bí mật nào trong image: mọi mật khẩu truyền qua biến môi trường lúc chạy.
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && cp target/backend-*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8080
# Máy dev ít RAM: giới hạn heap. Ghi đè bằng biến JAVA_OPTS trong docker-compose.
ENV JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseSerialGC"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
