FROM node:24-bookworm-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.13-eclipse-temurin-25 AS backend
WORKDIR /build
COPY --from=frontend /build/frontend/dist ./frontend/dist
COPY backend/ ./backend/
RUN mvn -B -f backend/pom.xml package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
COPY --from=backend /build/backend/target/omsweb-payment-1.0.0.jar ./omsweb.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/omsweb.jar"]
