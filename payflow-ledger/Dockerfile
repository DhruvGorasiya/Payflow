FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY payflow-common/pom.xml payflow-common/pom.xml
COPY payflow-api/pom.xml payflow-api/pom.xml
COPY payflow-transactions/pom.xml payflow-transactions/pom.xml
COPY payflow-ledger/pom.xml payflow-ledger/pom.xml
COPY payflow-notifications/pom.xml payflow-notifications/pom.xml
RUN mvn dependency:go-offline -pl payflow-ledger -am -q
COPY payflow-common/src payflow-common/src
COPY payflow-ledger/src payflow-ledger/src
RUN mvn package -pl payflow-ledger -am -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/payflow-ledger/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
