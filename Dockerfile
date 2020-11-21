FROM maven:3-openjdk-11 AS base

FROM base AS build

WORKDIR /build

COPY pom.xml /build/pom.xml
RUN mvn dependency:go-offline

COPY src /build/src
COPY aux /aux

RUN mvn install:install-file -Dfile=/aux/ids-utils-1.1.0-fat.jar -DgroupId=de.fraunhofer.fokus.ids -DartifactId=ids-utils -Dversion=1.1.0 -Dpackaging=jar
RUN mvn package

FROM base AS deploy
COPY --from=build /build/target/odc-manager-*-fat.jar /home/app/odc-manager.jar
WORKDIR /home/app

ENTRYPOINT ["java", "-jar", "odc-manager.jar"]
