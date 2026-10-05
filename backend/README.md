# Karaoke Studio AI backend

This is the independent processing service for Mobile v2. It replaces the old PC-pairing dependency.

## Endpoints
- GET /health
- POST /jobs/youtube-mp3
- POST /jobs/youtube-karaoke
- POST /jobs/upload-karaoke
- POST /jobs/master?preset=streaming|warm|punchy|clean
- GET /jobs/{job_id}
- POST /jobs/{job_id}/cancel
- GET /jobs/{job_id}/download

## Run with Docker
```
docker build -t karaoke-studio-ai .
docker run --rm -p 8000:8000 -v karaoke-data:/data karaoke-studio-ai
```

The first karaoke job downloads the configured separator model and later jobs reuse the cached model data when persistent storage is mounted.

For production, deploy behind HTTPS and use a real queue/worker plus persistent object storage. The YouTube workflow is intended only for content the user owns or is authorized to process.
