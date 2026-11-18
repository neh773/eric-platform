FROM python:3.12-slim-bookworm@sha256:6a0e06f5ba0b373d051aabd3fffb9b873f27f9a2a3a14bcbe37dee6f0e3bbc70
WORKDIR /app
COPY model.py modbus.py broker.py opcua.py publish.py server.py /app/
ENV PYTHONUNBUFFERED=1
EXPOSE 5020 4840 1883 8080
CMD ["python", "-u", "/app/server.py"]
