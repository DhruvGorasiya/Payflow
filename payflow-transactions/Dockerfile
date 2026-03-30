FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY payflow-common/pom.xml payflow-common/pom.xml
COPY payflow-api/pom.xml payflow-api/pom.xml
COPY payflow-transactions/pom.xml payflow-transactions/pom.xml
COPY payflow-ledger/pom.xml payflow-ledger/pom.xml
COPY payflow-notifications/pom.xml payflow-notifications/pom.xml
RUN mvn dependency:go-offline -pl payflow-transactions -am -q
COPY payflow-common/src payflow-common/src
COPY payflow-transactions/src payflow-transactions/src
RUN mvn package -pl payflow-transactions -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/payflow-transactions/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
