# Nhật Ký Chuyển Dự Án Lên Docker Ubuntu Server

Tài liệu này cung cấp các bước chi tiết để cấu hình và triển khai dự án lên Ubuntu Server thông qua Docker.

## Bước 1: Cài Đặt Docker Trên Ubuntu Server
Sau khi đã kết nối SSH thành công vào server, hãy chạy các lệnh sau để cập nhật hệ thống và cài đặt Docker:

```bash
sudo apt update
sudo apt install docker.io -y

# Kiểm tra xem Docker đã hoạt động chưa
sudo docker run hello-world
```

## Bước 2: Cấu Hình `application.yml` Cho Docker
Đây là bước quan trọng nhất. Trong môi trường Docker, các service không giao tiếp với nhau qua `localhost` mà sẽ giao tiếp thông qua tên của container. 

### 1. Cấu hình cho API Gateway và Registration Service
Ta cần sửa file cấu hình của hai module này để thay đổi đường dẫn Eureka.
* **Module API Gateway**: Sửa file `api-gateway-alb/src/main/resources/application.yml`.
* **Module Registration**: Sửa file `registration-service-alb/src/main/resources/application.yml` (lưu ý dùng `PORT` thay cho `server.port` ở phần `instance-id`).

Chỉ cần sửa lại đoạn cấu hình `eureka` như sau:
```yaml
eureka:
  client:
    serviceUrl:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
  instance:
    prefer-ip-address: true
    # Đối với registration-service, thêm dòng sau:
    # instance-id: ${spring.application.name}:${PORT:8081}
```

### 2. Cấu hình Eureka Server
Thêm biến môi trường cho `hostname` trong file `eureka-server/src/main/resources/application.yml`:
```yaml
server:
  port: 8761

eureka:
  instance:
    hostname: ${EUREKA_HOSTNAME:localhost}    # ← Thêm biến env này
  client:
    register-with-eureka: false
    fetch-registry: false
    service-url:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/
```

## Bước 3: Tạo Dockerfile Cho Các Module
Chúng ta sẽ sử dụng kỹ thuật Multi-stage build để tối ưu image. Quá trình này sẽ copy toàn bộ mã nguồn vào Docker để build ra file `.jar`.

### 1. Dockerfile cho `eureka-server`
Tạo file `Dockerfile` trong thư mục `eureka-server`:
```dockerfile
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/eureka-server/target/*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 2. Dockerfile cho `api-gateway-alb`
Tạo file `Dockerfile` trong thư mục `api-gateway-alb`:
```dockerfile
# --- Stage 1: Build ---
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build
# Copy toàn bộ mã nguồn vào Docker
COPY . .
# Build project
RUN mvn clean package -DskipTests

# --- Stage 2: Run ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Chỉ copy file .jar từ Stage 1
COPY --from=builder /build/api-gateway-alb/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 3. Dockerfile cho `registration-service-alb`
Tạo file `Dockerfile` trong thư mục `registration-service-alb`:
```dockerfile
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/registration-service-alb/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Bước 4: Khởi Tạo File `docker-compose.yml`
Tạo file `docker-compose.yml` tại thư mục gốc của toàn bộ project để kết nối các service. Các module sẽ trỏ `dockerfile` tới đúng vị trí tương ứng:

```yaml
version: '3.8'

networks:
  alb-network:
    driver: bridge

services:

  # ─── Eureka Service Discovery ──────────────────────────
  eureka-server:
    build: 
      context: .
      dockerfile: eureka-server/Dockerfile
    container_name: eureka-server
    environment:
      - EUREKA_HOSTNAME=eureka-server
    ports:
      - "8761:8761"
    networks:
      - alb-network
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8761/actuator/health | grep UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 40s

  # ─── Registration Service Instance 1 ───────────────────
  registration-8081:
    build: 
      context: .
      dockerfile: registration-service-alb/Dockerfile
    container_name: registration-8081
    environment:
      - PORT=8081
      - EUREKA_URL=http://eureka-server:8761/eureka/
    ports:
      - "8081:8081"
    networks:
      - alb-network
    depends_on:
      eureka-server:
        condition: service_healthy
    restart: on-failure

  # ─── Registration Service Instance 2 ───────────────────
  registration-8082:
    build: 
      context: .
      dockerfile: registration-service-alb/Dockerfile
    container_name: registration-8082
    environment:
      - PORT=8082
      - EUREKA_URL=http://eureka-server:8761/eureka/
    ports:
      - "8082:8082"
    networks:
      - alb-network
    depends_on:
      eureka-server:
        condition: service_healthy
    restart: on-failure

  # ─── Registration Service Instance 3 (Chaos Target) ────
  registration-8083:
    build: 
      context: .
      dockerfile: registration-service-alb/Dockerfile
    container_name: registration-8083
    environment:
      - PORT=8083
      - EUREKA_URL=http://eureka-server:8761/eureka/
    ports:
      - "8083:8083"
    networks:
      - alb-network
    depends_on:
      eureka-server:
        condition: service_healthy
    restart: on-failure

  # ─── API Gateway ALB ────────────────────────────────────
  api-gateway-alb:
    build: 
      context: .
      dockerfile: api-gateway-alb/Dockerfile
    container_name: api-gateway-alb
    environment:
      - EUREKA_URL=http://eureka-server:8761/eureka/
    ports:
      - "8080:8080"
    networks:
      - alb-network
    depends_on:
      eureka-server:
        condition: service_healthy
    restart: on-failure
```

## Bước 5: Đẩy Code Lên Git Và Chạy Trên Server
### Đẩy code lên Github
Thực hiện commit với thông điệp: *"Xóa thư mục target khỏi Git và cập nhật cấu trúc Docker Multi-stage"*.
```bash
git add .
git commit -m "Xóa thư mục target khỏi Git và cập nhật cấu trúc Docker Multi-stage"
git push
```

### Triển khai thủ công lần đầu trên Server
Tại thư mục gốc trên máy chủ Ubuntu, cài đặt `docker-compose-v2` và phân quyền cho người dùng:
```bash
sudo apt update
sudo apt install docker-compose-v2 -y
docker compose version
sudo usermod -aG docker $USER
exit
# Tiến hành SSH lại bằng lệnh: ssh tvhuy@172.30.35.37
```
Kéo mã nguồn và tiến hành build:
```bash
git clone [https://github.com/truongquycode/Adaptive-Load-Balancing-.git](https://github.com/truongquycode/Adaptive-Load-Balancing-.git)
cd Adaptive-Load-Balancing-
# Khởi động ở chế độ nền
docker compose build
docker compose up -d
```

### Quy trình cập nhật CI/CD tự động
Khi hệ thống có cập nhật mới từ Github, hệ thống sẽ được cập nhật chỉ với 3 lệnh đơn giản trên server:
```bash
git pull
docker compose build
docker compose up -d
```

## Bước 6: Kiểm Tra Hoạt Động Của Hệ Thống
* **Kiểm tra trạng thái các container:** `docker compose ps`. Kết quả mong đợi là các service như `eureka-server`, `registration-8081/8082/8083` và `api-gateway-alb` đều hiển thị trạng thái `running`.
* **Xem log Gateway:** `docker compose logs -f api-gateway-alb`.
* **Kiểm tra endpoint:** `curl http://172.30.35.37:8080/api/register`.
* **Truy cập Eureka Dashboard (qua Browser):** `http://172.30.35.37:8761`.

## Các Lệnh Quản Lý Hữu Ích Khác
* **Dừng toàn bộ hệ thống Docker Compose:** `docker compose down`.
* **Khởi động lại một service cụ thể:** `docker compose restart api-gateway-alb`.
* **Bật/tắt chế độ Chaos trong Docker:**
    * Bật: `curl -X POST http://172.30.35.37:8083/api/chaos/enable`
    * Tắt: `curl -X POST http://172.30.35.37:8083/api/chaos/disable`
* **Xem log cụ thể của một container:** `docker compose logs -f registration-8083`.
* **Chỉ cập nhật mã nguồn cho một service (ví dụ: api-gateway-alb):**
    ```bash
    docker compose build api-gateway-alb
    docker compose up -d --no-deps api-gateway-alb
    ```