# Build stage
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN apt-get update && apt-get install -y maven && \
    mvn package -DskipTests --batch-mode

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
VOLUME /tmp

ARG JAR_FILE=/workspace/target/*.jar
COPY --from=builder ${JAR_FILE} app.jar

RUN useradd -r -u 1001 -g root jolt-app && \
    chown jolt-app:root app.jar
USER jolt-app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
