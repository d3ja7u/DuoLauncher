FROM python:3.12-alpine

ENV PYTHONUNBUFFERED=1 \
    PORT=3000 \
    STATS_DATA_DIR=/data

WORKDIR /app
COPY server.py index.html cloud-config.json cloud-config.js ./

RUN adduser -D -H duo \
    && mkdir -p /data \
    && chown -R duo:duo /app /data

USER duo
EXPOSE 3000
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s \
  CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:3000/health', timeout=3)" || exit 1

CMD ["python3", "server.py"]
