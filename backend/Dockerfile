FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg libsndfile1 build-essential curl unzip ca-certificates && rm -rf /var/lib/apt/lists/*\nRUN curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

ENV KARAOKE_DATA_DIR=/data\nENV OMP_NUM_THREADS=1\nENV MKL_NUM_THREADS=1\nENV OPENBLAS_NUM_THREADS=1\nENV MALLOC_ARENA_MAX=2

EXPOSE 8000
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}"]
