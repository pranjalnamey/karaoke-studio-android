const KARAOKE_MODEL_URL = "https://huggingface.co/AI4future/RVC/resolve/main/UVR_MDXNET_KARA_2.onnx";
const KARAOKE_LIBRARY_URL = "https://esm.sh/web-audio-separation@0.3.0?bundle&deps=onnxruntime-web@1.29.0";
const SAMPLE_RATE = 44100;
const CLOUD_WORKER_URL = String(window.KARAOKE_CLOUD?.url || "").replace(/\/$/, "");
const CLOUD_WORKER_ENABLED = Boolean(window.KARAOKE_CLOUD?.enabled && CLOUD_WORKER_URL);

const $ = (id) => document.getElementById(id);
const workspace = $("workspace");
const workspaceBody = $("workspaceBody");
const audioPicker = $("audioPicker");

let currentMode = null;
let selectedFile = null;
let karaokeModule = null;
let activeSeparator = null;
let cancelRequested = false;
let generatedUrl = null;
let progressHeartbeat = null;
let lastProgressAt = 0;
let processingStartedAt = 0;
let activeCloudJobId = null;

init();

async function init() {
  updateResponsiveLabels();
  window.addEventListener("resize", updateResponsiveLabels);
  $("gpuMode").textContent = navigator.gpu ? "WebGPU available" : "WASM fallback";
  $("engineText").textContent = navigator.gpu ? "Karaoke AI · WebGPU" : "Karaoke AI · WASM";
  const profile = getProcessingProfile();
  const profileEl = $("processingProfile");
  if (profileEl) profileEl.textContent = profile.label;
  const wasmEl = $("wasmMode");
  if (wasmEl) wasmEl.textContent = crossOriginIsolated ? "Multithread-ready" : "Single-thread fallback";
  const cloudEl = $("cloudWorkerStatus");
  if (cloudEl) cloudEl.textContent = CLOUD_WORKER_ENABLED ? "Available" : "Not configured";
  document.querySelector(".status-dot").style.background = "var(--green)";
  await cleanupLegacyModelCache();
  await refreshModelStatus();

  document.querySelectorAll(".action-card").forEach((button) => {
    button.addEventListener("click", () => openTool(button.dataset.action));
  });
  $("closeWorkspace").addEventListener("click", closeWorkspace);
  audioPicker.addEventListener("change", () => {
    if (audioPicker.files?.[0]) {
      selectedFile = audioPicker.files[0];
      renderSelectedFile();
    }
  });
}

function updateResponsiveLabels() {
  $("layoutMode").textContent = window.innerWidth >= 900 ? "Desktop workspace" : "Mobile workspace";
}

async function refreshModelStatus() {
  const cached = await hasCachedModel();
  $("modelStatus").textContent = cached ? "Cached locally" : "First-use download";
}

function getProcessingProfile(forceLowMemory = false) {
  const ua = navigator.userAgent || "";
  const mobile = /Android|iPhone|iPad|iPod|Mobile/i.test(ua) || window.innerWidth < 760;
  const memory = Number(navigator.deviceMemory || 0);
  const constrained = forceLowMemory || mobile || (!navigator.gpu) || (memory > 0 && memory <= 4);

  if (constrained) {
    return {
      key: "low-memory",
      label: mobile ? "Mobile Low Memory" : "Low Memory",
      segmentSize: 128,
      overlap: 0.15,
      batchSize: 1,
      providers: navigator.gpu ? ["webgpu", "wasm"] : ["wasm"],
      detail: mobile
        ? "Mobile-safe AI settings are active to reduce RAM use."
        : "Reduced-memory AI settings are active."
    };
  }

  return {
    key: "standard",
    label: "Standard Quality",
    segmentSize: 256,
    overlap: 0.25,
    batchSize: 1,
    providers: ["webgpu", "wasm"],
    detail: "Standard desktop AI settings are active."
  };
}

function openTool(action) {
  currentMode = action;
  selectedFile = null;
  setProgress("Ready", 0, "Choose a source to begin.");
  workspace.hidden = false;
  workspace.scrollIntoView({ behavior: "smooth", block: "start" });

  if (action === "youtube-karaoke") renderYouTube(true);
  if (action === "youtube-mp3") renderYouTube(false);
  if (action === "upload-karaoke") renderUpload("karaoke");
  if (action === "master") renderUpload("master");
}

async function closeWorkspace() {
  cancelRequested = true;
  if (activeCloudJobId && CLOUD_WORKER_ENABLED) {
    try {
      const pin = localStorage.getItem("karaokeFamilyPin") || "";
      await fetch(CLOUD_WORKER_URL + "/jobs/" + activeCloudJobId + "/cancel", {
        method: "POST",
        headers: { "X-Karaoke-PIN": pin }
      });
    } catch {}
    activeCloudJobId = null;
  }
  if (generatedUrl) URL.revokeObjectURL(generatedUrl);
  generatedUrl = null;
  selectedFile = null;
  audioPicker.value = "";
  workspace.hidden = true;
  setProgress("Ready", 0, "Choose a tool to begin.");
}

function renderYouTube(karaoke) {
  $("workspaceEyebrow").textContent = "AUTHORIZED MEDIA";
  $("workspaceTitle").textContent = karaoke ? "YouTube → Karaoke" : "YouTube → MP3";
  workspaceBody.innerHTML = `
    <div class="field">
      <label for="ytUrl">YouTube link</label>
      <input class="text-input" id="ytUrl" inputmode="url" placeholder="https://youtube.com/watch?v=…" />
    </div>
    <div class="notice">
      Karaoke Studio opens Y2Mate in your normal browser. Only use content you own or are authorized to download/process.
    </div>
    <div class="button-row">
      <button class="primary" id="openY2Mate">Open Y2Mate</button>
      ${karaoke ? '<button class="secondary" id="importAfterDownload">Import downloaded audio</button>' : ""}
    </div>
  `;

  $("openY2Mate").addEventListener("click", async () => {
    const url = $("ytUrl").value.trim();
    if (!isYouTubeUrl(url)) {
      alert("Paste a valid YouTube URL first.");
      return;
    }
    try { await navigator.clipboard.writeText(url); } catch {}
    window.open("https://y2mate.gs/", "_blank", "noopener,noreferrer");
    setProgress("Converter opened", 5, "The YouTube link was copied when browser permissions allowed it.");
  });

  if (karaoke) {
    $("importAfterDownload").addEventListener("click", () => {
      currentMode = "upload-karaoke";
      audioPicker.click();
    });
  }
}

function isYouTubeUrl(value) {
  try {
    const u = new URL(value);
    const h = u.hostname.toLowerCase();
    return h === "youtu.be" || h === "youtube.com" || h.endsWith(".youtube.com");
  } catch { return false; }
}

function renderUpload(mode) {
  currentMode = mode === "master" ? "master" : "upload-karaoke";
  $("workspaceEyebrow").textContent = mode === "master" ? "LOCAL MASTERING" : "NEURAL VOCAL SEPARATION";
  $("workspaceTitle").textContent = mode === "master" ? "Master Audio" : "Upload → Karaoke";
  workspaceBody.innerHTML = `
    <div class="dropzone" id="dropzone">
      <strong>${mode === "master" ? "Choose an audio file to master" : "Choose an audio file for karaoke"}</strong>
      <p>${mode === "master"
        ? "Audio is decoded and mastered in this browser."
        : "UVR Karaoke 2 removes the lead vocal locally. Mobile automatically uses a lower-memory processing profile."}</p>
      <button class="primary" id="chooseFile">Choose audio</button>
    </div>
    <div id="selectedFile"></div>
  `;

  const dz = $("dropzone");
  $("chooseFile").addEventListener("click", () => audioPicker.click());
  ["dragenter","dragover"].forEach((event) => dz.addEventListener(event, (e) => {
    e.preventDefault(); dz.classList.add("drag");
  }));
  ["dragleave","drop"].forEach((event) => dz.addEventListener(event, (e) => {
    e.preventDefault(); dz.classList.remove("drag");
  }));
  dz.addEventListener("drop", (e) => {
    const file = e.dataTransfer.files?.[0];
    if (file && file.type.startsWith("audio/")) {
      selectedFile = file;
      renderSelectedFile();
    }
  });
}

function renderSelectedFile() {
  const host = $("selectedFile");
  if (!host || !selectedFile) return;
  const mb = (selectedFile.size / 1024 / 1024).toFixed(1);
  const karaoke = currentMode !== "master";
  const cloudButtons = karaoke && CLOUD_WORKER_ENABLED
    ? '<button class="primary" id="startCloudProcess">Create in Cloud</button><button class="secondary" id="startProcess">Process on this device</button>'
    : '<button class="primary" id="startProcess">' + (karaoke ? "Create professional karaoke" : "Master locally") + '</button>';

  host.innerHTML = `
    <div class="file-chip"><strong>${escapeHtml(selectedFile.name)}</strong><span>${mb} MB</span></div>
    ${karaoke ? '<div class="notice warn" id="modelNotice">The professional model is downloaded once and cached by your browser.</div><div class="notice" id="profileNotice"></div>' : ""}
    ${karaoke && CLOUD_WORKER_ENABLED ? '<div class="notice good">Cloud processing is recommended for phones. Your phone only uploads/downloads the audio; the AI work happens on the worker.</div>' : ""}
    <div class="button-row">
      ${cloudButtons}
      <button class="secondary" id="replaceFile">Choose another file</button>
    </div>
    <div id="resultHost"></div>
  `;

  $("replaceFile").addEventListener("click", () => audioPicker.click());
  const localButton = $("startProcess");
  if (localButton) localButton.addEventListener("click", karaoke ? runKaraoke : runMastering);
  const cloudButton = $("startCloudProcess");
  if (cloudButton) cloudButton.addEventListener("click", runCloudKaraoke);

  if (karaoke) {
    const profile = getProcessingProfile();
    const profileNote = $("profileNotice");
    if (profileNote) {
      profileNote.textContent = CLOUD_WORKER_ENABLED
        ? "Cloud available · Local fallback: " + profile.label
        : profile.label + " · " + profile.detail;
    }
    hasCachedModel().then((cached) => {
      const note = $("modelNotice");
      if (note) note.textContent = CLOUD_WORKER_ENABLED
        ? "Cloud mode does not need the AI model on this phone. Local fallback still uses the browser model."
        : (cached
            ? "UVR Karaoke 2 is already cached in this browser."
            : "First local run downloads the karaoke model once, then caches it for future songs.");
    });
  }
}

async function getCloudPin() {
  let pin = localStorage.getItem("karaokeFamilyPin") || "";
  if (!pin) {
    pin = (window.prompt("Enter the Karaoke Studio family PIN") || "").trim();
    if (pin) localStorage.setItem("karaokeFamilyPin", pin);
  }
  if (!pin) throw new Error("Family PIN is required for cloud processing.");
  return pin;
}

async function cloudFetch(path, options = {}, retryPin = true) {
  const pin = await getCloudPin();
  const headers = new Headers(options.headers || {});
  headers.set("X-Karaoke-PIN", pin);
  const response = await fetch(CLOUD_WORKER_URL + path, { ...options, headers });

  if (response.status === 401 && retryPin) {
    localStorage.removeItem("karaokeFamilyPin");
    return cloudFetch(path, options, false);
  }
  if (!response.ok) {
    let detail = "Cloud worker returned HTTP " + response.status;
    try {
      const body = await response.json();
      detail = body.detail || body.error || detail;
    } catch {}
    throw new Error(detail);
  }
  return response;
}

async function waitForCloudWorker() {
  const maxAttempts = 18;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    if (cancelRequested) throw new Error("Cancelled");

    setProgress(
      "Waking cloud worker",
      Math.min(4, 1 + Math.floor(attempt / 5)),
      attempt === 1
        ? "Starting the private Karaoke Studio worker. A cold start can take a few minutes."
        : "Cloud worker is still starting… attempt " + attempt + "/" + maxAttempts
    );

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(CLOUD_WORKER_URL + "/health", {
        method: "GET",
        cache: "no-store",
        signal: controller.signal
      });
      if (response.ok) {
        clearTimeout(timer);
        return true;
      }
    } catch {}
    clearTimeout(timer);

    if (attempt < maxAttempts) {
      await new Promise((resolve) => setTimeout(resolve, 5000));
    }
  }
  throw new Error("Cloud worker did not become ready in time. Please try again in a minute.");
}

async function runCloudKaraoke() {
  if (!selectedFile || !CLOUD_WORKER_ENABLED) return;
  cancelRequested = false;
  toggleProcessButtons(true);

  try {
    await waitForCloudWorker();
    setProgress("Uploading to cloud worker", 5, "Your phone is sending the audio to the private Karaoke Studio worker…");

    const form = new FormData();
    form.append("file", selectedFile, selectedFile.name);
    const response = await cloudFetch("/jobs/upload-bundle", {
      method: "POST",
      body: form
    });
    const created = await response.json();
    activeCloudJobId = created.job_id;
    if (!activeCloudJobId) throw new Error("Cloud worker did not return a job ID.");

    while (!cancelRequested) {
      const statusResponse = await cloudFetch("/jobs/" + activeCloudJobId);
      const job = await statusResponse.json();

      setProgress(
        job.stage || "Cloud processing",
        Number.isFinite(job.percent) ? job.percent : 25,
        job.detail || "Cloud AI is working…"
      );

      if (job.status === "completed") {
        await showCloudResults(
          activeCloudJobId,
          job.source_filename || "KaraokeStudio-Original-320k.mp3",
          job.karaoke_filename || "KaraokeStudio-Karaoke-320k.mp3"
        );
        setProgress("Complete", 100, "Both MP3 and karaoke are ready.");
        activeCloudJobId = null;
        return;
      }

      if (job.status === "failed") {
        throw new Error(job.error || job.detail || "Cloud processing failed.");
      }
      if (job.status === "cancelled") {
        throw new Error("Cancelled");
      }

      await new Promise((resolve) => setTimeout(resolve, 2000));
    }

    throw new Error("Cancelled");
  } catch (error) {
    const message = String(error?.message || error);
    if (message.toLowerCase().includes("cancelled")) {
      setProgress("Cancelled", 0, "Processing was cancelled.");
    } else {
      console.error(error);
      setProgress("Cloud processing failed", 0, friendlyError(error));
      const useLocal = window.confirm(
        friendlyError(error) + "\n\nWould you like to try processing on this device instead?"
      );
      if (useLocal) {
        activeCloudJobId = null;
        toggleProcessButtons(false);
        return runKaraoke();
      }
    }
  } finally {
    activeCloudJobId = null;
    toggleProcessButtons(false);
  }
}

async function showCloudResults(jobId, sourceName, karaokeName) {
  setProgress("Downloading results", 96, "Bringing the finished MP3 files back to this device…");

  const [sourceResponse, karaokeResponse] = await Promise.all([
    cloudFetch("/jobs/" + jobId + "/download/source"),
    cloudFetch("/jobs/" + jobId + "/download/karaoke")
  ]);

  const [sourceBlob, karaokeBlob] = await Promise.all([
    sourceResponse.blob(),
    karaokeResponse.blob()
  ]);

  const sourceUrl = URL.createObjectURL(sourceBlob);
  const karaokeUrl = URL.createObjectURL(karaokeBlob);

  const host = $("resultHost");
  if (!host) return;
  host.innerHTML = `
    <div class="result-card">
      <h3>Both files are ready</h3>
      <div class="notice good">The heavy AI processing happened on the cloud worker, not on this phone.</div>
      <p class="muted">Original MP3</p>
      <audio controls src="${sourceUrl}"></audio>
      <div class="button-row">
        <a class="primary" style="display:grid;place-items:center;text-decoration:none" href="${sourceUrl}" download="${escapeHtml(sourceName)}">Download MP3</a>
      </div>
      <p class="muted" style="margin-top:16px">Karaoke MP3</p>
      <audio controls src="${karaokeUrl}"></audio>
      <div class="button-row">
        <a class="primary" style="display:grid;place-items:center;text-decoration:none" href="${karaokeUrl}" download="${escapeHtml(karaokeName)}">Download Karaoke</a>
      </div>
    </div>
  `;
}

async function runKaraoke() {
  if (!selectedFile) return;
  cancelRequested = false;
  toggleProcessButtons(true);

  let inputUrl = null;
  let attemptedLowMemoryRetry = false;

  try {
    setProgress("Preparing karaoke AI", 2, "Loading the UVR Karaoke 2 browser engine…");
    const { createSeparator } = await ensureKaraokeLibrary();
    if (cancelRequested) throw new Error("Cancelled");

    inputUrl = URL.createObjectURL(selectedFile);

    const runAttempt = async (forceLowMemory = false) => {
      const profile = getProcessingProfile(forceLowMemory);
      const profileEl = $("processingProfile");
      if (profileEl) profileEl.textContent = profile.label;

      setProgress(
        forceLowMemory ? "Retrying in Low Memory mode" : "Preparing karaoke AI",
        3,
        profile.detail
      );

      activeSeparator = createSeparator("UVR_MDXNET_KARA_2", {
        common: {
          logLevel: "warning",
          outputSingleStem: "instrumental",
          onProgress: (progress) => {
            if (cancelRequested) return;
            lastProgressAt = Date.now();
            const fraction = Math.max(0, Math.min(1, progress.fraction || 0));

            if (progress.stage === "loading-model") {
              setProgress(
                "Loading UVR Karaoke 2",
                4 + Math.round(fraction * 16),
                fraction < 1
                  ? "First use downloads and caches the karaoke model in this browser."
                  : "Lead-vocal model ready."
              );
            } else if (progress.stage === "demixing") {
              const chunkText = progress.chunk && progress.totalChunks
                ? ` · chunk ${progress.chunk}/${progress.totalChunks}`
                : "";
              setProgress(
                "Removing lead vocal",
                20 + Math.round(fraction * 72),
                profile.label + " · Preserving backing vocals and choir where the model identifies them" + chunkText
              );
            } else if (progress.stage === "writing-output") {
              setProgress(
                "Preparing karaoke WAV",
                92 + Math.round(fraction * 7),
                "Writing the final instrumental + backing-vocal mix."
              );
            }
          },
        },
        mdx: {
          segmentSize: profile.segmentSize,
          overlap: profile.overlap,
          batchSize: profile.batchSize,
          hopLength: 1024,
          executionProviders: profile.providers,
        },
      });

      await activeSeparator.loadModel();
      if (cancelRequested) throw new Error("Cancelled");

      processingStartedAt = Date.now();
      lastProgressAt = Date.now();
      startProgressHeartbeat(profile);

      try {
        const stemUrls = await activeSeparator.separate(inputUrl);
        if (cancelRequested) throw new Error("Cancelled");
        if (!stemUrls?.length) throw new Error("The karaoke model did not produce an output stem.");
        return stemUrls;
      } finally {
        stopProgressHeartbeat();
      }
    };

    let stemUrls;
    try {
      stemUrls = await runAttempt(false);
    } catch (firstError) {
      const message = String(firstError?.message || firstError);
      const alreadyLow = getProcessingProfile(false).key === "low-memory";
      const retryable = /memory|allocation|wasm|webgpu|device|buffer|tensor/i.test(message);
      if (!alreadyLow && retryable && !cancelRequested) {
        attemptedLowMemoryRetry = true;
        setProgress(
          "Switching to Low Memory mode",
          3,
          "The first attempt was too heavy for this device. Retrying with smaller AI chunks…"
        );
        await nextPaint();
        stemUrls = await runAttempt(true);
      } else {
        throw firstError;
      }
    }

    showResultUrl(
      stemUrls[0],
      "KaraokeStudio-LeadRemoved.wav",
      attemptedLowMemoryRetry ? "Karaoke ready · Low Memory mode" : "Karaoke ready · backing vocals preserved"
    );
    setProgress(
      "Complete",
      100,
      attemptedLowMemoryRetry
        ? "Completed using the lower-memory mobile profile."
        : "Lead vocal removed. Backing vocals/choir are retained where the model can distinguish them."
    );
    await refreshModelStatus();
  } catch (error) {
    stopProgressHeartbeat();
    if (String(error?.message || error).toLowerCase().includes("cancelled")) {
      setProgress("Cancelled", 0, "Processing was cancelled.");
    } else {
      console.error(error);
      setProgress("Could not finish", 0, friendlyError(error));
      alert(friendlyError(error));
    }
  } finally {
    stopProgressHeartbeat();
    if (inputUrl) URL.revokeObjectURL(inputUrl);
    activeSeparator = null;
    toggleProcessButtons(false);
  }
}

function startProgressHeartbeat(profile) {
  stopProgressHeartbeat();
  progressHeartbeat = setInterval(() => {
    if (!processingStartedAt || cancelRequested) return;
    const idleSeconds = Math.floor((Date.now() - lastProgressAt) / 1000);
    const totalSeconds = Math.floor((Date.now() - processingStartedAt) / 1000);
    if (idleSeconds >= 20) {
      const percent = Number(String($("progressPercent").textContent || "20").replace("%", "")) || 20;
      const mins = Math.floor(totalSeconds / 60);
      const secs = totalSeconds % 60;
      const elapsed = mins > 0 ? `${mins}m ${secs}s` : `${secs}s`;
      setProgress(
        "AI is still processing",
        percent,
        `${profile.label} · First chunks can take longer on phones. Elapsed: ${elapsed}. Keep this tab open.`
      );
    }
  }, 5000);
}

function stopProgressHeartbeat() {
  if (progressHeartbeat) {
    clearInterval(progressHeartbeat);
    progressHeartbeat = null;
  }
}

async function runMastering() {
  if (!selectedFile) return;
  cancelRequested = false;
  toggleProcessButtons(true);
  try {
    setProgress("Decoding audio", 10, "Reading the song locally…");
    const audio = await decodeAndResample(selectedFile, SAMPLE_RATE);
    const offline = new OfflineAudioContext(audio.numberOfChannels, audio.length, audio.sampleRate);
    const src = offline.createBufferSource();
    src.buffer = audio;

    const low = offline.createBiquadFilter();
    low.type = "highpass";
    low.frequency.value = 28;

    const presence = offline.createBiquadFilter();
    presence.type = "peaking";
    presence.frequency.value = 3200;
    presence.Q.value = 0.8;
    presence.gain.value = 1.2;

    const comp = offline.createDynamicsCompressor();
    comp.threshold.value = -16;
    comp.knee.value = 20;
    comp.ratio.value = 3;
    comp.attack.value = 0.012;
    comp.release.value = 0.25;

    const gain = offline.createGain();
    gain.gain.value = 1.1;

    src.connect(low).connect(presence).connect(comp).connect(gain).connect(offline.destination);
    src.start();
    setProgress("Mastering", 45, "Applying EQ, compression and gain staging…");
    const rendered = await offline.startRendering();
    setProgress("Encoding WAV", 88, "Preparing download…");
    const blob = audioBufferToWav(rendered);
    showResult(blob, "KaraokeStudio-Mastered.wav", "Master ready");
    setProgress("Complete", 100, "Mastering finished locally.");
  } catch (error) {
    console.error(error);
    setProgress("Could not finish", 0, friendlyError(error));
    alert(friendlyError(error));
  } finally {
    toggleProcessButtons(false);
  }
}

async function ensureKaraokeLibrary() {
  if (karaokeModule) return karaokeModule;
  karaokeModule = await import(KARAOKE_LIBRARY_URL);
  return karaokeModule;
}

async function decodeAndResample(file, targetRate) {
  const data = await file.arrayBuffer();
  const ctx = new AudioContext();
  const decoded = await ctx.decodeAudioData(data.slice(0));
  await ctx.close();

  if (decoded.sampleRate === targetRate && decoded.numberOfChannels <= 2) return decoded;

  const channels = Math.min(2, Math.max(1, decoded.numberOfChannels));
  const length = Math.ceil(decoded.duration * targetRate);
  const offline = new OfflineAudioContext(channels, length, targetRate);
  const src = offline.createBufferSource();
  src.buffer = decoded;
  src.connect(offline.destination);
  src.start();
  return offline.startRendering();
}

async function hasCachedModel() {
  if (!("caches" in window)) return false;
  try {
    const cache = await caches.open("web-demix2-models");
    return Boolean(await cache.match(KARAOKE_MODEL_URL));
  } catch { return false; }
}

async function cleanupLegacyModelCache() {
  if (!("caches" in window)) return;
  try {
    // v4.0 cached a much larger HT-Demucs model. It is no longer used.
    await caches.delete("karaoke-studio-model-v1");
  } catch {}
}

function showResultUrl(url, filename, heading) {
  if (generatedUrl && generatedUrl !== url) URL.revokeObjectURL(generatedUrl);
  generatedUrl = url;
  const host = $("resultHost");
  if (!host) return;
  host.innerHTML = `
    <div class="result-card">
      <h3>${escapeHtml(heading)}</h3>
      <audio controls src="${generatedUrl}"></audio>
      <div class="notice good">This output is designed to keep backing vocals/group choir while removing the lead singer. Results still depend on how the original song was mixed.</div>
      <div class="button-row">
        <a class="primary" style="display:grid;place-items:center;text-decoration:none" href="${generatedUrl}" download="${filename}">Download WAV</a>
      </div>
    </div>
  `;
}

function showResult(blob, filename, heading) {
  if (generatedUrl) URL.revokeObjectURL(generatedUrl);
  generatedUrl = URL.createObjectURL(blob);
  const host = $("resultHost");
  if (!host) return;
  host.innerHTML = `
    <div class="result-card">
      <h3>${escapeHtml(heading)}</h3>
      <audio controls src="${generatedUrl}"></audio>
      <div class="button-row">
        <a class="primary" style="display:grid;place-items:center;text-decoration:none" href="${generatedUrl}" download="${filename}">Download WAV</a>
      </div>
    </div>
  `;
}

function setProgress(stage, percent, detail) {
  $("progressStage").textContent = stage;
  $("progressPercent").textContent = `${Math.max(0, Math.min(100, percent))}%`;
  $("progressBar").style.width = `${Math.max(0, Math.min(100, percent))}%`;
  $("progressDetail").textContent = detail;
}

function toggleProcessButtons(disabled) {
  const button = $("startProcess");
  if (button) button.disabled = disabled;
  const cloudButton = $("startCloudProcess");
  if (cloudButton) cloudButton.disabled = disabled;
}

function floatToI16(x) {
  const v = Math.max(-1, Math.min(1, x));
  return Math.round(v * 32767);
}

function wavHeader(dataBytes, sampleRate, channels) {
  const buffer = new ArrayBuffer(44);
  const view = new DataView(buffer);
  const write = (offset, text) => [...text].forEach((c, i) => view.setUint8(offset + i, c.charCodeAt(0)));
  write(0, "RIFF");
  view.setUint32(4, 36 + dataBytes, true);
  write(8, "WAVE");
  write(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, channels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * channels * 2, true);
  view.setUint16(32, channels * 2, true);
  view.setUint16(34, 16, true);
  write(36, "data");
  view.setUint32(40, dataBytes, true);
  return new Uint8Array(buffer);
}

function audioBufferToWav(buffer) {
  const channels = Math.min(2, buffer.numberOfChannels);
  const frames = buffer.length;
  const pcm = new Uint8Array(frames * channels * 2);
  const view = new DataView(pcm.buffer);
  const channelData = Array.from({ length: channels }, (_, c) => buffer.getChannelData(c));
  let o = 0;
  for (let i = 0; i < frames; i++) {
    for (let c = 0; c < channels; c++) {
      view.setInt16(o, floatToI16(channelData[c][i]), true);
      o += 2;
    }
  }
  return new Blob([wavHeader(pcm.byteLength, buffer.sampleRate, channels), pcm], { type: "audio/wav" });
}

function friendlyError(error) {
  const message = String(error?.message || error || "Unknown browser processing error.");
  if (/out of memory|allocation|memory|buffer|tensor/i.test(message)) {
    return "This device ran out of memory during karaoke separation. Mobile Low Memory mode is used automatically, but very old/low-RAM phones may still need a shorter song or a desktop browser.";
  }
  if (/webgpu/i.test(message)) {
    return "WebGPU could not start on this device. Karaoke Studio will normally fall back to WebAssembly; try current Chrome or Edge.";
  }
  return message;
}

function loadScript(src) {
  return new Promise((resolve, reject) => {
    const existing = [...document.scripts].find((s) => s.src === src);
    if (existing) { resolve(); return; }
    const script = document.createElement("script");
    script.src = src;
    script.onload = resolve;
    script.onerror = () => reject(new Error("Could not load the browser AI runtime."));
    document.head.appendChild(script);
  });
}

function nextPaint() {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
  }[c]));
}
