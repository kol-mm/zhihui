# syntax=docker/dockerfile:1.7
FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AI_DB_PATH=/data/ai/ai_service.db \
    AI_VECTOR_MODE=local

RUN groupadd --gid 10001 app && \
    useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app && \
    mkdir -p /app /data/ai && \
    chown -R app:app /app /data

WORKDIR /app
COPY ai-service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY --chown=app:app ai-service/app ./app

USER app
EXPOSE 8200
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8200", "--workers", "1"]

