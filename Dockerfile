# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -DskipTests package \
 && cp target/*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
# ffmpeg provides ffprobe, which MaterialContentValidator requires for MP4/WebM validation.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app \
 && mkdir -p /data/lms-files /data/class-thumbnails && chown -R app:app /data
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
ENV LMS_FFPROBE_EXECUTABLE=/usr/bin/ffprobe \
    LMS_STORAGE_LOCAL_DIRECTORY=/data/lms-files \
    LMS_CLASS_THUMBNAIL_DIRECTORY=/data/class-thumbnails \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER 10001:10001
EXPOSE 8081
# Any HTTP response (including 401 from the secured API) proves the servlet is serving; 000 means down.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
  CMD test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/api/v1/classes)" != "000" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
