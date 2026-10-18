FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY backend/requirements.txt backend/constraints.txt ./
RUN pip install --no-cache-dir -r requirements.txt -c constraints.txt \
    && groupadd --gid 10001 sakura \
    && useradd --uid 10001 --gid sakura --no-create-home sakura
COPY --chown=10001:10001 backend/sakura ./sakura
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "sakura.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--limit-concurrency", "12", "--no-access-log"]
