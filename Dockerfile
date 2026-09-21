FROM eclipse-temurin:21-jre-alpine

RUN addgroup --system --gid 10001 ledgerx \
    && adduser --system --uid 10001 --ingroup ledgerx ledgerx

WORKDIR /app
COPY target/ledgerx-0.0.1-SNAPSHOT.jar app.jar

USER ledgerx
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
