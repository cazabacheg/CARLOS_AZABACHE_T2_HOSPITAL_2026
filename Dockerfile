# syntax=docker/dockerfile:1
# Imagen de cualquier servicio del monorepo. Uso: --build-arg MODULE=pacientes-service
# La etapa "build" no depende de MODULE: se construye UNA vez y se reutiliza (cache) para los 8 modulos.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY hospital-commons/pom.xml hospital-commons/pom.xml
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY pacientes-service/pom.xml pacientes-service/pom.xml
COPY citas-service/pom.xml citas-service/pom.xml
COPY atenciones-service/pom.xml atenciones-service/pom.xml
COPY pagos-service/pom.xml pagos-service/pom.xml
COPY notificaciones-service/pom.xml notificaciones-service/pom.xml
COPY auditoria-service/pom.xml auditoria-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline -DskipTests || true
COPY . .
# Las pruebas se ejecutan en el CI (./mvnw verify); la imagen solo empaqueta.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime
ARG MODULE
ARG OTEL_VERSION=2.31.1
ARG OTEL_SHA256=bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home hvs \
    && mkdir -p /opt/otel \
    && curl -fsSL -o /opt/otel/agent.jar \
       "https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_VERSION}/opentelemetry-javaagent-${OTEL_VERSION}.jar" \
    && echo "${OTEL_SHA256}  /opt/otel/agent.jar" | sha256sum -c -
WORKDIR /app
COPY --from=build /app/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar app.jar
USER hvs
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/agent.jar -XX:MaxRAMPercentage=70"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
