# AI Research Hub 后端镜像(Phase 12)
# 构建镜像需要访问 Maven 仓库;本机当前 Docker 引擎不可用,此文件供可运行 Docker 的环境使用
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/ai-research-hub-backend-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
