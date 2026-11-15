# syntax=docker/dockerfile:1.7

# One image definition for all four Spring Boot modules. Compose passes SERVICE
# as a build argument, so each image only packages its own executable jar while
# sharing the Maven dependency cache layers.
FROM maven:3.9.9-eclipse-temurin-17 AS build
ARG SERVICE
WORKDIR /workspace

# Reactor descriptors first: dependency downloads are cached until a POM changes.
COPY pom.xml ./
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY instrument-service/pom.xml instrument-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml

RUN mvn -B -q -pl "${SERVICE}" -am dependency:go-offline

COPY . .
RUN mvn -B -q -pl "${SERVICE}" -am package -DskipTests
RUN cp "${SERVICE}"/target/"${SERVICE}"-*.jar /workspace/application.jar

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/application.jar /app/app.jar
RUN mkdir -p /app/uploads && chown -R app:app /app
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
