# Self-contained image for the revenue department service (fake data only, never a live source).
# Built from the departments/ folder (the build context), because the service uses the shared kit module:
#   docker build -f departments/revenue/Dockerfile departments
# The citizen portal pages are served from this service's static/portal/ folder. They are a generated copy of the front end:
# run scripts/build-portal.sh BEFORE building the image (deploy/README.md).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN mvn -B -ntp -DskipTests -pl revenue -am package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/revenue/target/samanvay-dept-revenue.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
