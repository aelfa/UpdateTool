FROM openjdk:11-jre-slim AS build
RUN apt-get update && apt-get install -y wget binutils xz-utils tree
WORKDIR /usr/app
COPY docker-shared/*.sh /usr/app/
RUN /usr/app/download.sh
RUN /usr/app/plexSqliteBinaries.sh

FROM eclipse-temurin:21.0.6_7-jdk AS deploy
WORKDIR /usr/app
COPY --from=build /usr/app/plexsqlitedriver /usr/app/plexsqlitedriver
COPY --from=build /usr/app/tool.jar /usr/app/
COPY docker-shared/*.sh /usr/app/
VOLUME /config
WORKDIR /config
ENTRYPOINT /usr/app/entry.sh
