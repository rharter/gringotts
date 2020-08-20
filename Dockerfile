FROM adoptopenjdk/openjdk8:alpine-slim as builder

WORKDIR /app
COPY . .

RUN ./gradlew build --no-daemon --stacktrace

FROM adoptopenjdk/openjdk8:alpine-jre

ENV APPLICATION_USER ktor
RUN adduser -D -g '' $APPLICATION_USER

RUN mkdir -p /app/lib \
    && chown -R $APPLICATION_USER /app

USER $APPLICATION_USER

WORKDIR /app

COPY --from=builder /app/server/build/libs/server.jar /app/lib/server.jar
COPY --from=builder /app/server/build/scriptsShadow/server /app/lib/server

ENV JAVA_OPTS "-server -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:InitialRAMFraction=2 -XX:MinRAMFraction=2 -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"

CMD ["/app/lib/server"]
