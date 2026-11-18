FROM node:22-bookworm-slim@sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d
WORKDIR /app
COPY opcua/package.json opcua/package-lock.json ./
RUN npm ci --omit=dev
COPY opcua/server.js opcua/waveform.js ./
ENV NODE_ENV=production
EXPOSE 4840 8080
# Runs as root so node-opcua can write its ephemeral self-signed test certificate
# into the workdir. This image is a test double, not a gateway.
CMD ["node", "server.js"]
