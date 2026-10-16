# Community Vortex analysis service. Build after `./mvnw package`:
#   docker build -t community-vortex-server vortex-server
FROM node:26.11.1-bookworm-slim AS node

FROM eclipse-temurin:21.0.12_8-jre-noble
# Node.js for the JavaScript/TypeScript analyzer
COPY --from=node /usr/local/bin/node /usr/local/bin/node
RUN useradd --system --uid 10001 --home-dir /var/lib/vortex vortex \
  && mkdir -p /var/lib/vortex /opt/vortex \
  && chown vortex /var/lib/vortex
COPY target/vortex-server-all.jar /opt/vortex/vortex-server.jar
USER vortex
ENV VORTEX_CACHE_DIR=/var/lib/vortex VORTEX_PORT=9100 VORTEX_NODE=/usr/local/bin/node
EXPOSE 9100
HEALTHCHECK --interval=30s --timeout=5s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9100"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/vortex/vortex-server.jar"]
