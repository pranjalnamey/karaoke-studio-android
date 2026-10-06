import os
import shutil
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Dict, Optional

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

APP_DIR = Path(os.getenv("KARAOKE_DATA_DIR", "/tmp/karaoke-studio-cloud"))
APP_DIR.mkdir(parents=True, exist_ok=True)
MODEL_DIR = Path(os.getenv("KARAOKE_MODEL_DIR", "/models"))
FAMILY_PIN = os.getenv("KARAOKE_FAMILY_PIN", "").strip()
MAX_UPLOAD_MB = int(os.getenv("KARAOKE_MAX_UPLOAD_MB", "80"))
JOB_TTL_SECONDS = int(os.getenv("KARAOKE_JOB_TTL_SECONDS", "3600"))

origins_env = os.getenv(
    "KARAOKE_ALLOWED_ORIGINS",
    "https://nice-water-0f0dfe300.1.azurestaticapps.net,http://localhost:8000,http://127.0.0.1:8000",
)
ALLOWED_ORIGINS = [x.strip() for x in origins_env.split(",") if x.strip()]

app = FastAPI(title="Karaoke Studio Cloud Worker", version="1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-Karaoke-PIN"],
)

JOBS: Dict[str, dict] = {}
LOCK = threading.Lock()
EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="karaoke-worker")
ACTIVE_PROCESSES: Dict[str, subprocess.Popen] = {}


def now() -> float:
    return time.time()


def check_pin(x_karaoke_pin: Optional[str]):
    if not FAMILY_PIN:
        raise HTTPException(status_code=503, detail="Cloud worker family PIN is not configured.")
    if x_karaoke_pin != FAMILY_PIN:
        raise HTTPException(status_code=401, detail="Invalid family PIN.")


def set_job(job_id: str, **values):
    with LOCK:
        job = JOBS.setdefault(job_id, {})
        job.update(values)
        job["updated_at"] = now()


def get_job_or_404(job_id: str) -> dict:
    with LOCK:
        job = JOBS.get(job_id)
        if not job:
            raise HTTPException(status_code=404, detail="Job not found or expired.")
        job["last_seen_at"] = now()
        return dict(job)


def job_dir(job_id: str) -> Path:
    d = APP_DIR / job_id
    d.mkdir(parents=True, exist_ok=True)
    return d


def is_cancelled(job_id: str) -> bool:
    with LOCK:
        return JOBS.get(job_id, {}).get("status") == "cancelled"


def run_process(job_id: str, cmd, cwd=None):
    log_path = job_dir(job_id) / "worker.log"
    with log_path.open("a", encoding="utf-8", errors="replace") as log:
        proc = subprocess.Popen(
            cmd,
            cwd=cwd,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        with LOCK:
            ACTIVE_PROCESSES[job_id] = proc
        try:
            while proc.poll() is None:
                if is_cancelled(job_id):
                    proc.terminate()
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                    raise RuntimeError("Cancelled")
                time.sleep(0.5)
            if proc.returncode != 0:
                tail = ""
                try:
                    tail = log_path.read_text(encoding="utf-8", errors="replace")[-5000:]
                except Exception:
                    pass
                if proc.returncode in (-9, 137):
                    raise RuntimeError(
                        "The cloud worker ran out of memory while separating this song."
                    )
                raise RuntimeError(tail or f"Audio command failed with exit code {proc.returncode}.")
        finally:
            with LOCK:
                ACTIVE_PROCESSES.pop(job_id, None)


def ffmpeg_normalize(job_id: str, src: Path, dst: Path):
    run_process(job_id, [
        "ffmpeg", "-y", "-i", str(src),
        "-ar", "44100", "-ac", "2",
        "-c:a", "pcm_s16le", str(dst)
    ])


def ffmpeg_mp3(job_id: str, src: Path, dst: Path):
    run_process(job_id, [
        "ffmpeg", "-y", "-i", str(src),
        "-c:a", "libmp3lame", "-b:a", "320k",
        str(dst)
    ])


def ffmpeg_master(job_id: str, src: Path, dst: Path):
    chain = (
        "highpass=f=28,"
        "equalizer=f=250:t=q:w=1.1:g=-0.6,"
        "equalizer=f=5000:t=q:w=1.0:g=0.8,"
        "acompressor=threshold=-19dB:ratio=2.4:attack=12:release=180,"
        "loudnorm=I=-14:TP=-1:LRA=8,"
        "alimiter=limit=0.89"
    )
    run_process(job_id, [
        "ffmpeg", "-y", "-i", str(src),
        "-af", chain,
        "-c:a", "libmp3lame", "-b:a", "320k",
        str(dst)
    ])


def choose_instrumental(out_dir: Path) -> Path:
    candidates = list(out_dir.glob("*Instrumental*.wav")) + list(out_dir.glob("*instrumental*.wav"))
    if not candidates:
        candidates = [p for p in out_dir.glob("*.wav") if "vocal" not in p.name.lower()]
    if not candidates:
        raise RuntimeError("The AI separator produced no karaoke output.")
    return sorted(candidates, key=lambda p: p.stat().st_size, reverse=True)[0]


def separate_karaoke(job_id: str, src: Path, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    run_process(job_id, [
        "audio-separator", str(src),
        "-m", "UVR_MDXNET_KARA_2.onnx",
        "--model_file_dir", str(MODEL_DIR),
        "--output_dir", str(out_dir),
        "--output_format", "WAV",
        "--single_stem", "Instrumental",
        "--mdx_segment_size", "256",
        "--mdx_batch_size", "1",
        "--mdx_overlap", "0.25",
        "--log_level", "info",
    ])
    return choose_instrumental(out_dir)


def process_bundle(job_id: str, input_path: Path):
    work = job_dir(job_id)
    try:
        if is_cancelled(job_id):
            return

        set_job(
            job_id,
            status="processing",
            stage="Preparing audio",
            percent=8,
            detail="Creating a clean 44.1 kHz stereo source.",
        )
        normalized = work / "normalized.wav"
        ffmpeg_normalize(job_id, input_path, normalized)

        set_job(
            job_id,
            stage="Creating MP3",
            percent=18,
            detail="Creating the original-quality 320 kbps MP3.",
        )
        source_mp3 = work / "KaraokeStudio-Original-320k.mp3"
        ffmpeg_mp3(job_id, normalized, source_mp3)

        set_job(
            job_id,
            stage="Separating lead vocal",
            percent=25,
            detail="UVR Karaoke 2 is running on the cloud worker. This is the longest step.",
        )
        instrumental = separate_karaoke(job_id, normalized, work / "separated")

        set_job(
            job_id,
            stage="Finishing karaoke",
            percent=88,
            detail="Applying final loudness control and encoding the karaoke MP3.",
        )
        karaoke_mp3 = work / "KaraokeStudio-Karaoke-320k.mp3"
        ffmpeg_master(job_id, instrumental, karaoke_mp3)

        if is_cancelled(job_id):
            return

        set_job(
            job_id,
            status="completed",
            stage="Ready",
            percent=100,
            detail="Both files are ready.",
            source_filename=source_mp3.name,
            karaoke_filename=karaoke_mp3.name,
            source_path=str(source_mp3),
            karaoke_path=str(karaoke_mp3),
        )
    except Exception as exc:
        if str(exc).lower() == "cancelled" or is_cancelled(job_id):
            set_job(job_id, status="cancelled", stage="Cancelled", percent=0, detail="The job was cancelled.")
        else:
            set_job(
                job_id,
                status="failed",
                stage="Failed",
                percent=0,
                detail="The cloud worker could not finish this song.",
                error=str(exc),
            )


def cleanup_loop():
    while True:
        time.sleep(300)
        cutoff = now() - JOB_TTL_SECONDS
        stale = []
        with LOCK:
            for job_id, job in list(JOBS.items()):
                stamp = job.get("last_seen_at") or job.get("updated_at") or job.get("created_at") or 0
                if stamp < cutoff and job.get("status") not in {"processing", "queued"}:
                    stale.append(job_id)
        for job_id in stale:
            shutil.rmtree(APP_DIR / job_id, ignore_errors=True)
            with LOCK:
                JOBS.pop(job_id, None)


threading.Thread(target=cleanup_loop, daemon=True).start()


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "karaoke-studio-cloud-worker",
        "version": "1.0",
        "model": "UVR_MDXNET_KARA_2.onnx",
        "max_upload_mb": MAX_UPLOAD_MB,
        "queue": "single-job",
    }


@app.post("/jobs/upload-bundle")
def upload_bundle(
    file: UploadFile = File(...),
    x_karaoke_pin: Optional[str] = Header(default=None),
):
    check_pin(x_karaoke_pin)

    job_id = uuid.uuid4().hex
    work = job_dir(job_id)
    safe_name = Path(file.filename or "upload.bin").name
    input_path = work / safe_name

    max_bytes = MAX_UPLOAD_MB * 1024 * 1024
    total = 0
    try:
        with input_path.open("wb") as out:
            while True:
                chunk = file.file.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise HTTPException(
                        status_code=413,
                        detail=f"Audio file is larger than the {MAX_UPLOAD_MB} MB family limit.",
                    )
                out.write(chunk)
    except Exception:
        shutil.rmtree(work, ignore_errors=True)
        raise

    set_job(
        job_id,
        status="queued",
        stage="Queued",
        percent=2,
        detail="Waiting for the cloud audio worker.",
        created_at=now(),
        last_seen_at=now(),
        input_name=safe_name,
    )
    EXECUTOR.submit(process_bundle, job_id, input_path)
    return {"job_id": job_id}


@app.get("/jobs/{job_id}")
def job_status(job_id: str, x_karaoke_pin: Optional[str] = Header(default=None)):
    check_pin(x_karaoke_pin)
    data = get_job_or_404(job_id)
    data.pop("source_path", None)
    data.pop("karaoke_path", None)
    if data.get("status") == "completed":
        data["source_download_url"] = f"/jobs/{job_id}/download/source"
        data["karaoke_download_url"] = f"/jobs/{job_id}/download/karaoke"
    return data


@app.post("/jobs/{job_id}/cancel")
def cancel_job(job_id: str, x_karaoke_pin: Optional[str] = Header(default=None)):
    check_pin(x_karaoke_pin)
    get_job_or_404(job_id)
    set_job(job_id, status="cancelled", stage="Cancelling", percent=0, detail="Stopping the current job.")
    with LOCK:
        proc = ACTIVE_PROCESSES.get(job_id)
    if proc and proc.poll() is None:
        try:
            proc.terminate()
        except Exception:
            pass
    return {"ok": True}


def result_file(job_id: str, key: str, filename_key: str) -> FileResponse:
    job = get_job_or_404(job_id)
    if job.get("status") != "completed":
        raise HTTPException(status_code=409, detail="The result is not ready yet.")
    path = Path(job.get(key, ""))
    if not path.is_file():
        raise HTTPException(status_code=404, detail="The result has expired.")
    return FileResponse(path, media_type="audio/mpeg", filename=job.get(filename_key, path.name))


@app.get("/jobs/{job_id}/download/source")
def download_source(job_id: str, x_karaoke_pin: Optional[str] = Header(default=None)):
    check_pin(x_karaoke_pin)
    return result_file(job_id, "source_path", "source_filename")


@app.get("/jobs/{job_id}/download/karaoke")
def download_karaoke(job_id: str, x_karaoke_pin: Optional[str] = Header(default=None)):
    check_pin(x_karaoke_pin)
    return result_file(job_id, "karaoke_path", "karaoke_filename")
