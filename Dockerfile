FROM maven:3.9.16-eclipse-temurin-21-noble AS build

WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup --system --gid 10001 ledgerx \
    && adduser --system --uid 10001 --ingroup ledgerx ledgerx

WORKDIR /app
COPY --from=build /workspace/target/ledgerx-0.0.1-SNAPSHOT.jar app.jar

USER ledgerx
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
