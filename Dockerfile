FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN chmod +x mvnw && (./mvnw -o -q -DskipTests package || ./mvnw -q -DskipTests package)

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system agentops && useradd --system --gid agentops --home-dir /app agentops
WORKDIR /app
COPY --from=build --chown=agentops:agentops /workspace/target/agentops-gate-*.jar app.jar
USER agentops:agentops
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
