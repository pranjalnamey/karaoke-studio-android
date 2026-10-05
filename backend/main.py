import asyncio
import os
import shutil
import subprocess
import threading
import time
import uuid
from pathlib import Path
from typing import Dict, Optional

from fastapi import FastAPI, BackgroundTasks, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel

APP_DIR = Path(os.getenv("KARAOKE_DATA_DIR", "/tmp/karaoke-studio"))
APP_DIR.mkdir(parents=True, exist_ok=True)
JOBS: Dict[str, dict] = {}
LOCK = threading.Lock()

app = FastAPI(title="Karaoke Studio AI", version="2.2")


class YoutubeRequest(BaseModel):
    url: str
    master: bool = False
    keep_backing_vocals: bool = True


def set_job(job_id: str, **values):
    with LOCK:
        JOBS.setdefault(job_id, {}).update(values)


def job_dir(job_id: str) -> Path:
    d = APP_DIR / job_id
    d.mkdir(parents=True, exist_ok=True)
    return d


def run(cmd, cwd=None):
    p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if p.returncode != 0:
        if p.returncode in (-9, 137):
            raise RuntimeError(
                "Audio separation ran out of cloud memory. "
                "The low-memory worker could not complete this track."
            )
        raise RuntimeError(p.stdout[-4000:])
    return p.stdout


def ffmpeg_master(src: Path, dst: Path, preset: str):
    chains = {
        "clean": "highpass=f=28,equalizer=f=300:t=q:w=1.2:g=-1.0,acompressor=threshold=-18dB:ratio=2.2:attack=15:release=180,loudnorm=I=-14:TP=-1:LRA=9,alimiter=limit=0.89",
        "warm": "highpass=f=28,equalizer=f=180:t=q:w=1.0:g=1.5,equalizer=f=7500:t=q:w=1.0:g=-0.8,acompressor=threshold=-19dB:ratio=2.5:attack=18:release=220,loudnorm=I=-14:TP=-1:LRA=8,alimiter=limit=0.89",
        "punchy": "highpass=f=30,equalizer=f=90:t=q:w=1.2:g=1.4,equalizer=f=3500:t=q:w=1.0:g=1.2,acompressor=threshold=-20dB:ratio=3.2:attack=8:release=120,loudnorm=I=-12.5:TP=-1:LRA=7,alimiter=limit=0.89",
        "streaming": "highpass=f=28,equalizer=f=250:t=q:w=1.1:g=-0.6,equalizer=f=5000:t=q:w=1.0:g=0.8,acompressor=threshold=-19dB:ratio=2.4:attack=12:release=180,loudnorm=I=-14:TP=-1:LRA=8,alimiter=limit=0.89",
    }
    chain = chains.get(preset, chains["streaming"])
    run(["ffmpeg", "-y", "-i", str(src), "-af", chain, "-c:a", "libmp3lame", "-b:a", "320k", str(dst)])


def normalize_audio(src: Path, dst: Path):
    run(["ffmpeg", "-y", "-i", str(src), "-ar", "44100", "-ac", "2", str(dst)])


def extract_youtube_audio(url: str, work: Path) -> Path:
    template = str(work / "source.%(ext)s")
    try:
        run([
            "yt-dlp", "-f", "bestaudio/best", "--no-playlist",
            "--js-runtimes", "deno",
            "--extract-audio", "--audio-format", "wav", "--audio-quality", "0",
            "-o", template, url
        ])
    except RuntimeError as exc:
        raw = str(exc)
        lower = raw.lower()
        if "http error 429" in lower or "confirm you're not a bot" in lower or "confirm you’re not a bot" in lower:
            raise RuntimeError(
                "YouTube temporarily blocked this cloud server (HTTP 429 / bot verification). "
                "The karaoke engine is online, but YouTube refused this server's request. "
                "Please try again later or use Upload → Karaoke for this source."
            ) from None
        raise
    wav = work / "source.wav"
    if not wav.exists():
        matches = list(work.glob("source.*"))
        if not matches:
            raise RuntimeError("Audio extraction produced no file.")
        normalize_audio(matches[0], wav)
    return wav


def choose_instrumental(out_dir: Path) -> Path:
    candidates = list(out_dir.glob("*Instrumental*.wav")) + list(out_dir.glob("*instrumental*.wav"))
    if not candidates:
        candidates = [p for p in out_dir.glob("*.wav") if "vocal" not in p.name.lower()]
    if not candidates:
        raise RuntimeError("Separator produced no instrumental output.")
    return sorted(candidates, key=lambda p: p.stat().st_size, reverse=True)[0]


def separate_karaoke(src: Path, out_dir: Path, keep_backing_vocals: bool) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    model_dir = APP_DIR / "models"
    model_dir.mkdir(parents=True, exist_ok=True)
    model = "UVR_MDXNET_KARA_2.onnx" if keep_backing_vocals else "UVR-MDX-NET-Inst_HQ_3.onnx"
    run([
        "audio-separator", str(src),
        "-m", model,
        "--model_file_dir", str(model_dir),
        "--output_dir", str(out_dir),
        "--output_format", "WAV",
        "--single_stem", "Instrumental",
        "--mdx_segment_size", "128",
        "--mdx_batch_size", "1",
        "--mdx_overlap", "0.15",
        "--log_level", "info",
    ])
    return choose_instrumental(out_dir)


def save_upload(upload: UploadFile, path: Path):
    with path.open("wb") as f:
        shutil.copyfileobj(upload.file, f)


def process_youtube_mp3(job_id: str, req: YoutubeRequest):
    work = job_dir(job_id)
    try:
        set_job(job_id, status="processing", stage="Extracting authorized audio", detail="Retrieving the highest-quality available audio stream.")
        src = extract_youtube_audio(req.url, work)
        out = work / "YouTube-Audio.mp3"
        if req.master:
            set_job(job_id, stage="Mastering audio", detail="Balancing loudness, tone and peak level.")
            ffmpeg_master(src, out, "streaming")
        else:
            set_job(job_id, stage="Encoding MP3", detail="Creating a 320 kbps MP3.")
            run(["ffmpeg", "-y", "-i", str(src), "-c:a", "libmp3lame", "-b:a", "320k", str(out)])
        set_job(job_id, status="completed", stage="Ready", detail="Your MP3 is ready.", filename=out.name, download_url=f"/jobs/{job_id}/download", result=str(out))
    except Exception as e:
        set_job(job_id, status="failed", error=str(e), stage="Failed", detail="The job could not be completed.")


def process_youtube_karaoke(job_id: str, req: YoutubeRequest):
    work = job_dir(job_id)
    try:
        set_job(job_id, status="processing", stage="Extracting authorized audio", detail="Preparing the source audio.")
        src = extract_youtube_audio(req.url, work)
        norm = work / "normalized.wav"
        normalize_audio(src, norm)

        set_job(job_id, stage="Separating lead vocals", detail="AI separation is running. Backing vocals are preserved when the model can isolate them.")
        instrumental = separate_karaoke(norm, work / "separated", req.keep_backing_vocals)

        out = work / "KaraokeStudio-karaoke.mp3"
        if req.master:
            set_job(job_id, stage="Mastering karaoke", detail="Polishing clarity, loudness and final peak level.")
            ffmpeg_master(instrumental, out, "streaming")
        else:
            set_job(job_id, stage="Encoding MP3", detail="Creating the final 320 kbps karaoke track.")
            run(["ffmpeg", "-y", "-i", str(instrumental), "-c:a", "libmp3lame", "-b:a", "320k", str(out)])

        set_job(job_id, status="completed", stage="Ready", detail="Your karaoke track is ready.", filename=out.name, download_url=f"/jobs/{job_id}/download", result=str(out))
    except Exception as e:
        set_job(job_id, status="failed", error=str(e), stage="Failed", detail="The job could not be completed.")


def process_upload_karaoke(job_id: str, input_path: Path):
    work = job_dir(job_id)
    try:
        set_job(job_id, status="processing", stage="Preparing audio", detail="Converting the upload to a stable processing format.")
        norm = work / "normalized.wav"
        normalize_audio(input_path, norm)

        set_job(job_id, stage="Separating lead vocals", detail="AI separation is running. Backing vocals are preserved when possible.")
        instrumental = separate_karaoke(norm, work / "separated", True)

        out = work / "KaraokeStudio-karaoke.mp3"
        set_job(job_id, stage="Mastering karaoke", detail="Applying final polish and loudness control.")
        ffmpeg_master(instrumental, out, "streaming")

        set_job(job_id, status="completed", stage="Ready", detail="Your karaoke track is ready.", filename=out.name, download_url=f"/jobs/{job_id}/download", result=str(out))
    except Exception as e:
        set_job(job_id, status="failed", error=str(e), stage="Failed", detail="The job could not be completed.")


def process_master(job_id: str, input_path: Path, preset: str):
    work = job_dir(job_id)
    try:
        set_job(job_id, status="processing", stage="Analyzing and mastering", detail=f"Applying the {preset} mastering profile.")
        out = work / f"KaraokeStudio-{preset}-master.mp3"
        ffmpeg_master(input_path, out, preset)
        set_job(job_id, status="completed", stage="Ready", detail="Your mastered track is ready.", filename=out.name, download_url=f"/jobs/{job_id}/download", result=str(out))
    except Exception as e:
        set_job(job_id, status="failed", error=str(e), stage="Failed", detail="The job could not be completed.")


@app.get("/health")
def health():
    return {"status": "ok", "version": "2.2", "youtube_js_runtime": "deno", "separator_mode": "low-memory"}


@app.post("/jobs/youtube-mp3")
def youtube_mp3(req: YoutubeRequest, background_tasks: BackgroundTasks):
    job_id = uuid.uuid4().hex
    set_job(job_id, status="queued", stage="Queued", detail="Waiting for a processing worker.")
    background_tasks.add_task(process_youtube_mp3, job_id, req)
    return {"job_id": job_id}


@app.post("/jobs/youtube-karaoke")
def youtube_karaoke(req: YoutubeRequest, background_tasks: BackgroundTasks):
    job_id = uuid.uuid4().hex
    set_job(job_id, status="queued", stage="Queued", detail="Waiting for a processing worker.")
    background_tasks.add_task(process_youtube_karaoke, job_id, req)
    return {"job_id": job_id}


@app.post("/jobs/upload-karaoke")
def upload_karaoke(background_tasks: BackgroundTasks, file: UploadFile = File(...)):
    job_id = uuid.uuid4().hex
    work = job_dir(job_id)
    input_path = work / (file.filename or "upload.bin")
    save_upload(file, input_path)
    set_job(job_id, status="queued", stage="Queued", detail="Waiting for a processing worker.")
    background_tasks.add_task(process_upload_karaoke, job_id, input_path)
    return {"job_id": job_id}


@app.post("/jobs/master")
def master(background_tasks: BackgroundTasks, preset: str = "streaming", file: UploadFile = File(...)):
    if preset not in {"streaming", "warm", "punchy", "clean"}:
        raise HTTPException(status_code=400, detail="Unknown mastering preset.")
    job_id = uuid.uuid4().hex
    work = job_dir(job_id)
    input_path = work / (file.filename or "upload.bin")
    save_upload(file, input_path)
    set_job(job_id, status="queued", stage="Queued", detail="Waiting for a processing worker.")
    background_tasks.add_task(process_master, job_id, input_path, preset)
    return {"job_id": job_id}


@app.get("/jobs/{job_id}")
def get_job(job_id: str):
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found.")
    data = dict(job)
    data.pop("result", None)
    return data


@app.post("/jobs/{job_id}/cancel")
def cancel_job(job_id: str):
    if job_id in JOBS:
        set_job(job_id, status="cancelled", stage="Cancelled", detail="The job was cancelled.")
    return {"ok": True}


@app.get("/jobs/{job_id}/download")
def download(job_id: str):
    job = JOBS.get(job_id)
    if not job or job.get("status") != "completed":
        raise HTTPException(status_code=404, detail="Result not ready.")
    result = Path(job["result"])
    if not result.exists():
        raise HTTPException(status_code=404, detail="Result file expired.")
    return FileResponse(result, media_type="audio/mpeg", filename=job.get("filename", result.name))
