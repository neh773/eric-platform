# Gateway image, configured with environment variables (no file edit required):
#   OIP_DATA_DIR, OIP_PROFILE, OIP_HTTP_HOST, OIP_HTTP_PORT, OIP_FEATURES, OIP_ADMIN_PASSWORD
# Opt-in HTTPS (off when unset): OIP_HTTP_HTTPS, OIP_HTTP_HTTPS_PORT, OIP_HTTP_PUBLIC_ADDRESS,
# OIP_HTTP_PUBLIC_HTTPS_PORT, OIP_HTTP_FORCE_REDIRECT, OIP_HTTPS_CERT, OIP_HTTPS_KEY,
# OIP_HTTPS_PKCS12, OIP_HTTPS_PKCS12_PASSWORD. See docs/ops/https.md.
# OIP_ADMIN_PASSWORD seeds user admin on first start when the user source is empty.
# Pass it at runtime. Do not bake a password into the image. See docs/ops/http-auth.md.
# Base images are pinned by digest. The runtime user can write /data and cannot write /opt/oip.
FROM eclipse-temurin:21-jdk@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew :gateway:server:installDist --no-daemon

FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
RUN useradd --system --no-create-home --uid 10001 --shell /usr/sbin/nologin oip \
    && mkdir -p /data \
    && chown oip:oip /data \
    && chmod 755 /data
WORKDIR /opt/oip
COPY --from=build --chown=root:root /src/gateway/server/build/install/server/ ./
COPY --chown=root:root scripts/gateway-healthcheck.sh /opt/oip/healthcheck.sh
RUN chmod 755 /opt/oip/healthcheck.sh /opt/oip/bin/server \
    && chmod -R a-w /opt/oip \
    && chmod 755 /opt/oip/healthcheck.sh /opt/oip/bin/server \
    && find /opt/oip -type d -exec chmod 755 {} +
USER oip
ENV OIP_DATA_DIR=/data \
    OIP_PROFILE=standard \
    OIP_HTTP_HOST=0.0.0.0 \
    OIP_HTTP_PORT=8088
EXPOSE 8088
VOLUME /data
HEALTHCHECK --interval=5s --timeout=3s --start-period=25s --retries=12 CMD ["/opt/oip/healthcheck.sh"]
ENTRYPOINT ["/opt/oip/bin/server"]
