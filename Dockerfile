FROM maven:3-openjdk-11 AS base

FROM base AS build

COPY src /build/src
COPY pom.xml /build/pom.xml
WORKDIR /build
RUN mvn package

FROM base AS deploy
COPY --from=build /build/target/odc-manager-*-fat.jar /home/app/odc-manager.jar
WORKDIR /home/app

ENTRYPOINT ["java", "-jar", "odc-manager.jar"]
