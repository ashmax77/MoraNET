FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
COPY config ./config
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /workspace/target/campusnet-1.0-SNAPSHOT.jar app.jar
COPY --from=build /workspace/config/server.properties config/server.properties
RUN mkdir -p storage

EXPOSE 5000/tcp 5001/tcp 8888/udp 8080/tcp

ENTRYPOINT ["java", "-jar", "app.jar"]
CMD ["config/server.properties"]
