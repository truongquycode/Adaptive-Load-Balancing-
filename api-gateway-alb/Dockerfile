# --- Stage 1: Build ---
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build
# Copy toàn bộ mã nguồn của cả project vào Docker
COPY . .
# Build toàn bộ project (sinh ra các thư mục target)
RUN mvn clean package -DskipTests

# --- Stage 2: Run ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Chỉ copy duy nhất file .jar của module api-gateway-alb từ Stage 1 sang
COPY --from=builder /build/api-gateway-alb/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]