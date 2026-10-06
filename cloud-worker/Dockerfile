FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg libsndfile1 build-essential ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Bake the karaoke model into the image so scale-to-zero cold starts do not
# need to download the model again.
RUN mkdir -p /models && \
    audio-separator -m UVR_MDXNET_KARA_2.onnx \
      --model_file_dir /models \
      --download_model_only

COPY main.py .

ENV KARAOKE_DATA_DIR=/tmp/karaoke-studio-cloud \
    KARAOKE_MODEL_DIR=/models \
    KARAOKE_MAX_UPLOAD_MB=80 \
    KARAOKE_JOB_TTL_SECONDS=3600 \
    OMP_NUM_THREADS=2 \
    MKL_NUM_THREADS=2 \
    OPENBLAS_NUM_THREADS=2 \
    MALLOC_ARENA_MAX=2

EXPOSE 8000

CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1"]
