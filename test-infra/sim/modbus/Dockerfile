FROM python:3.12-slim-bookworm@sha256:6a0e06f5ba0b373d051aabd3fffb9b873f27f9a2a3a14bcbe37dee6f0e3bbc70
WORKDIR /app
COPY common /app/common
COPY modbus/server.py /app/server.py
ENV PYTHONPATH=/app
ENV PYTHONUNBUFFERED=1
EXPOSE 5020 8080
# Test double. Port 5020 avoids binding privileged 502.
CMD ["python", "-u", "/app/server.py", "--modbus-port", "5020", "--http-port", "8080"]
