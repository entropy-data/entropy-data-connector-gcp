# maven build
FROM eclipse-temurin:17 AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY .git .git
COPY pom.xml .
COPY src src

RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode install -DskipTests
RUN java -Djarmode=tools -jar /app/target/*.jar extract --layers --launcher --destination /app/extracted

# docker image
FROM eclipse-temurin:17-jre
RUN useradd app
USER app
WORKDIR /app
ENV OTEL_JAVAAGENT_ENABLED=false
# Without this, the JVM caps the heap at 25% of the container memory. Overriding JAVA_TOOL_OPTIONS at
# runtime replaces this value entirely, so any override must repeat the flags that should be kept.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
COPY --from=build /app/extracted/dependencies/ ./
COPY --from=build /app/extracted/spring-boot-loader/ ./
COPY --from=build /app/extracted/snapshot-dependencies/ ./
COPY --from=build /app/extracted/application/ ./
