# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
# Maven Wrapper distribution (MVNW_REPOURL) and dependency downloads (settings.xml mirror) are
# independently configurable. Both default to Maven Central; set the build args for a mirror.
# The pinned SHA-256 of apache-maven-3.9.12-bin.tar.gz (the Linux wrapper download; verified against archive.apache.org SHA-512)
# makes the wrapper reject any tampered distribution, whatever the source.
ARG MVNW_REPOURL=
ARG MAVEN_MIRROR_URL=
ARG MAVEN_DIST_SHA256=fa2c9948729296c23afd18fd01a90f62cdda09a46191b54a8bc3764c2eee812e
ENV MVNW_REPOURL=$MVNW_REPOURL
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN echo "distributionSha256Sum=${MAVEN_DIST_SHA256}" >> .mvn/wrapper/maven-wrapper.properties \
 && echo '<settings/>' > /root/maven-settings.xml \
 && if [ -n "$MAVEN_MIRROR_URL" ]; then printf '<settings><mirrors><mirror><id>configured-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/maven-settings.xml; fi
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -s /root/maven-settings.xml --strict-checksums dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -s /root/maven-settings.xml --strict-checksums -DskipTests package \
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
