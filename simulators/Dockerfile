# Self-contained image for the department simulators. The main app's contract
# IT (IfscBankSimulatorContractIT) builds this with Testcontainers, so the
# main build needs Docker (as its Postgres ITs already do) but no Maven
# dependency on this module.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline
COPY src src
RUN mvn -B -q -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/samanvay-simulators.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
