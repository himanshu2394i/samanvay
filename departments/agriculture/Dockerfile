# Self-contained image for the agriculture department stand-in service (fake data only, never a live source).
# The build stage runs the department's OWN tests (`verify`, incl. its ArchUnit boundary rule), so a red
# test fails the image build.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/samanvay-dept-agriculture.jar app.jar
EXPOSE 8094
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
