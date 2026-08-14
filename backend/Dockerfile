# Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# メモリ上限を明示する。無指定だと JVM が自前でコンテナから割合を決めるため、
# インスタンスサイズを変えたときに実効ヒープが把握できなくなる。
#  - MaxRAMPercentage=65: 残り 35% をメタスペース/スレッドスタック/GC 構造/ネイティブに残す
#  - MaxMetaspaceSize:    クラスメタデータの上限を固定し、際限なく増えないようにする
#  - HeapDumpOnOutOfMemoryError: OOM 時に原因を追えるようにする
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=65", \
  "-XX:MaxMetaspaceSize=256m", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
  "-jar", "app.jar"]
