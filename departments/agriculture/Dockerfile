# Self-contained image for the agriculture department service (fake data only, never a live source).
# Built from the departments/ folder (the build context), because the service uses the shared kit module:
#   docker build -f departments/agriculture/Dockerfile departments
# The citizen portal pages are served from this service's static/portal/ folder. They are a generated copy of the front end:
# run scripts/build-portal.sh BEFORE building the image (deploy/README.md).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -ntp -DskipTests -pl agriculture -am package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/agriculture/target/samanvay-dept-agriculture.jar app.jar
EXPOSE 8094
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
