# B3: 多阶段构建。基础镜像使用本机已有的 daocloud 镜像源 tag，
# 构建期 Maven 依赖走阿里云镜像（本机网络对 repo.maven.apache.org 存在 TLS 劫持）。
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw.cmd .
COPY src src
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/central</url></mirror></mirrors></settings>' \
    > /root/.m2/settings.xml \
    && mvn -q -DskipTests package

FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
