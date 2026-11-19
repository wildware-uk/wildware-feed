# The Kotlin server as a container image.
#
# Build context: the installed distribution, not the repository (the Gradle build also configures
# the Android and iOS apps, which a container build should not need):
#
#   ./gradlew --no-daemon :server:installDist
#   docker build -f server/deploy/Dockerfile -t wildware-feed-server server/build/install/feed-server
#
# Same settings as the TypeScript server's image: the same environment variables, PORT 8010,
# DATA_DIR /data, and the same CLI names inside (`agent-dashboard`, `mint-token`, `hash-password`).

FROM eclipse-temurin:21-jdk-noble AS runtime
COPY lib /opt/feed-server/lib
# A Java runtime with only the modules the server uses (about 60 MB instead of 200).
RUN modules="$(jdeps --ignore-missing-deps --print-module-deps --multi-release 21 /opt/feed-server/lib/*.jar)" \
	&& jlink --add-modules "$modules,jdk.crypto.ec,jdk.zipfs,jdk.localedata" \
		--strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /jre

FROM debian:bookworm-slim
RUN apt-get update \
	&& apt-get install -y --no-install-recommends ca-certificates ffmpeg \
	&& rm -rf /var/lib/apt/lists/* \
	&& ffmpeg -version | head -1
COPY --from=runtime /jre /opt/java
COPY lib /opt/feed-server/lib
COPY bin /opt/feed-server/bin
# The web app, served at the site's root. The server finds it beside lib/.
COPY web /opt/feed-server/web
ENV JAVA_HOME=/opt/java
ENV PATH=/opt/java/bin:/opt/feed-server/bin:$PATH
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
ENV PORT=8010
ENV DATA_DIR=/data
RUN printf '#!/bin/sh\nexec feed-server "$@"\n' > /usr/local/bin/agent-dashboard \
	&& printf '#!/bin/sh\nexec feed-server mint-token "$@"\n' > /usr/local/bin/mint-token \
	&& printf '#!/bin/sh\nexec feed-server hash-password "$@"\n' > /usr/local/bin/hash-password \
	&& chmod +x /usr/local/bin/agent-dashboard /usr/local/bin/mint-token /usr/local/bin/hash-password \
	&& useradd --system --uid 10001 --home-dir /data feed \
	&& mkdir -p /data && chown feed:feed /data
USER feed
EXPOSE 8010
VOLUME ["/data"]
# Healthy when the port answers. There is no unauthenticated health route to ask, on purpose.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
	CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT:-8010}' || exit 1
STOPSIGNAL SIGTERM
CMD ["feed-server"]
