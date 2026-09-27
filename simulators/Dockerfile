# Self-contained image for the department simulators. The main app's contract
# IT (IfscBankSimulatorContractIT) builds this with Testcontainers, so the
# main build needs Docker (as its Postgres ITs already do) but no Maven
# dependency on this module.
#
# The build stage runs the simulator's OWN tests (`verify`, incl. its ArchUnit
# boundary rule), so the existing CI `./mvnw verify` builds and tests this
# module too without a separate workflow step: a red simulator test fails the
# image build, and with it the contract IT.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp verify

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/samanvay-simulators.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
