const MODEL_URL = "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx";
const ORT_VERSION = "1.22.0";
const SEGMENT = 343980;
const OVERLAP = Math.floor(SEGMENT / 4);
const STRIDE = SEGMENT - OVERLAP;
const SAMPLE_RATE = 44100;
const VOCAL_ROW = 3;

const $ = (id) => document.getElementById(id);
const workspace = $("workspace");
const workspaceBody = $("workspaceBody");
const audioPicker = $("audioPicker");

let currentMode = null;
let selectedFile = null;
let ortLoaded = false;
let ortSession = null;
let cancelRequested = false;
let generatedUrl = null;

init();

async function init() {
  updateResponsiveLabels();
  window.addEventListener("resize", updateResponsiveLabels);
  $("gpuMode").textContent = navigator.gpu ? "WebGPU available" : "WASM fallback";
  $("engineText").textContent = navigator.gpu ? "WebGPU capable" : "WASM capable";
  document.querySelector(".status-dot").style.background = "var(--green)";
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

function closeWorkspace() {
  cancelRequested = true;
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
        : "HT-Demucs separates the vocal stem locally. Desktop is recommended for long songs; mobile support depends on available memory."}</p>
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
  host.innerHTML = `
    <div class="file-chip"><strong>${escapeHtml(selectedFile.name)}</strong><span>${mb} MB</span></div>
    ${karaoke ? '<div class="notice warn" id="modelNotice">The professional model is downloaded once and cached by your browser.</div>' : ""}
    <div class="button-row">
      <button class="primary" id="startProcess">${karaoke ? "Create professional karaoke" : "Master locally"}</button>
      <button class="secondary" id="replaceFile">Choose another file</button>
    </div>
    <div id="resultHost"></div>
  `;
  $("replaceFile").addEventListener("click", () => audioPicker.click());
  $("startProcess").addEventListener("click", karaoke ? runKaraoke : runMastering);
  if (karaoke) {
    hasCachedModel().then((cached) => {
      const note = $("modelNotice");
      if (note) note.textContent = cached
        ? "HT-Demucs model is already cached in this browser."
        : "First run will download approximately 166 MB once, then cache it for future songs.";
    });
  }
}

async function runKaraoke() {
  if (!selectedFile) return;
  cancelRequested = false;
  toggleProcessButtons(true);
  try {
    setProgress("Preparing AI", 1, "Loading ONNX Runtime Web…");
    await ensureOrt();

    setProgress("Loading model", 3, "Checking browser cache…");
    const modelBytes = await getModelBytes((p) => setProgress("Downloading AI model", Math.max(3, Math.round(p * 18)), "One-time HT-Demucs model download"));
    if (cancelRequested) throw new Error("Cancelled");

    setProgress("Creating AI session", 20, navigator.gpu ? "Trying WebGPU first…" : "Using WebAssembly fallback…");
    ortSession = await createSession(modelBytes);

    setProgress("Decoding audio", 23, "Reading the selected song locally…");
    const audio = await decodeAndResample(selectedFile, SAMPLE_RATE);
    if (cancelRequested) throw new Error("Cancelled");

    const left = audio.getChannelData(0);
    const right = audio.numberOfChannels > 1 ? audio.getChannelData(1) : left;
    const total = audio.length;
    const chunks = Math.max(1, Math.ceil(total / STRIDE));
    const window = makeWindow();

    let vocalL = new Float32Array(SEGMENT);
    let vocalR = new Float32Array(SEGMENT);
    let weights = new Float32Array(SEGMENT);
    let base = 0;
    const wavParts = [];
    let dataBytes = 0;

    for (let chunkIndex = 0; chunkIndex < chunks; chunkIndex++) {
      if (cancelRequested) throw new Error("Cancelled");

      const start = chunkIndex * STRIDE;
      const chunkLen = Math.min(SEGMENT, total - start);
      const input = new Float32Array(2 * SEGMENT);
      input.set(left.subarray(start, start + chunkLen), 0);
      input.set(right.subarray(start, start + chunkLen), SEGMENT);

      const tensor = new ort.Tensor("float32", input, [1, 2, SEGMENT]);
      const results = await ortSession.run({ mix: tensor });
      const stemsTensor = results.stems || results[Object.keys(results)[0]];
      if (!stemsTensor?.data) throw new Error("HT-Demucs returned no stem data.");
      const stems = stemsTensor.data;

      const vLeftOffset = (VOCAL_ROW * 2) * SEGMENT;
      const vRightOffset = vLeftOffset + SEGMENT;

      for (let k = 0; k < chunkLen; k++) {
        const w = window[k];
        vocalL[k] += stems[vLeftOffset + k] * w;
        vocalR[k] += stems[vRightOffset + k] * w;
        weights[k] += w;
      }

      const last = chunkIndex === chunks - 1;
      const flushEnd = last ? total : Math.min(total, (chunkIndex + 1) * STRIDE);
      const flushCount = Math.max(0, flushEnd - base);
      const pcm = new Uint8Array(flushCount * 4);
      const view = new DataView(pcm.buffer);

      for (let k = 0; k < flushCount; k++) {
        const w = weights[k] < 1e-8 ? 1e-8 : weights[k];
        const vl = vocalL[k] / w;
        const vr = vocalR[k] / w;
        const mixL = left[base + k] ?? 0;
        const mixR = right[base + k] ?? 0;
        const instL = softLimit(mixL - vl);
        const instR = softLimit(mixR - vr);
        view.setInt16(k * 4, floatToI16(instL), true);
        view.setInt16(k * 4 + 2, floatToI16(instR), true);
      }

      wavParts.push(pcm);
      dataBytes += pcm.byteLength;

      if (!last) {
        const keep = SEGMENT - flushCount;
        vocalL.copyWithin(0, flushCount, SEGMENT); vocalL.fill(0, keep);
        vocalR.copyWithin(0, flushCount, SEGMENT); vocalR.fill(0, keep);
        weights.copyWithin(0, flushCount, SEGMENT); weights.fill(0, keep);
        base += flushCount;
      }

      const pct = 28 + Math.round(((chunkIndex + 1) / chunks) * 68);
      setProgress("AI separating vocals", Math.min(96, pct), `Segment ${chunkIndex + 1} of ${chunks}`);
      await nextPaint();
    }

    const blob = new Blob([wavHeader(dataBytes, SAMPLE_RATE, 2), ...wavParts], { type: "audio/wav" });
    showResult(blob, "KaraokeStudio-AI.wav", "Professional karaoke ready");
    setProgress("Complete", 100, "Vocal separation finished locally.");
  } catch (error) {
    if (String(error?.message || error).toLowerCase().includes("cancelled")) {
      setProgress("Cancelled", 0, "Processing was cancelled.");
    } else {
      console.error(error);
      setProgress("Could not finish", 0, friendlyError(error));
      alert(friendlyError(error));
    }
  } finally {
    toggleProcessButtons(false);
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

async function ensureOrt() {
  if (ortLoaded && window.ort) return;
  const src = navigator.gpu
    ? `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/ort.webgpu.min.js`
    : `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/ort.min.js`;
  await loadScript(src);
  ort.env.wasm.wasmPaths = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/`;
  ort.env.wasm.numThreads = 1;
  ortLoaded = true;
}

async function createSession(bytes) {
  const options = { graphOptimizationLevel: "disabled" };
  if (navigator.gpu) {
    try {
      const session = await ort.InferenceSession.create(bytes, { ...options, executionProviders: ["webgpu"] });
      $("gpuMode").textContent = "WebGPU active";
      $("engineText").textContent = "WebGPU active";
      return session;
    } catch (e) {
      console.warn("WebGPU session failed, falling back to WASM", e);
    }
  }
  $("gpuMode").textContent = "WASM active";
  $("engineText").textContent = "WASM active";
  return ort.InferenceSession.create(bytes, { ...options, executionProviders: ["wasm"] });
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

function makeWindow() {
  const w = new Float32Array(SEGMENT);
  w.fill(1);
  for (let i = 0; i < OVERLAP; i++) {
    const v = i / (OVERLAP - 1);
    w[i] = v;
    w[SEGMENT - 1 - i] = v;
  }
  return w;
}

async function hasCachedModel() {
  if (!("caches" in window)) return false;
  try {
    const cache = await caches.open("karaoke-studio-model-v1");
    return Boolean(await cache.match(MODEL_URL));
  } catch { return false; }
}

async function getModelBytes(onProgress) {
  const cache = "caches" in window ? await caches.open("karaoke-studio-model-v1") : null;
  const cached = cache ? await cache.match(MODEL_URL) : null;
  if (cached) {
    onProgress?.(1);
    await refreshModelStatus();
    return new Uint8Array(await cached.arrayBuffer());
  }

  const response = await fetch(MODEL_URL, { mode: "cors" });
  if (!response.ok) throw new Error(`AI model download failed: HTTP ${response.status}`);
  const total = Number(response.headers.get("content-length")) || 0;
  if (!response.body) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (cache) await cache.put(MODEL_URL, new Response(bytes, { headers: { "content-type": "application/octet-stream" } }));
    onProgress?.(1);
    await refreshModelStatus();
    return bytes;
  }

  const cachePromise = cache ? cache.put(MODEL_URL, response.clone()).catch(() => {}) : Promise.resolve();
  const reader = response.body.getReader();
  const chunks = [];
  let received = 0;
  while (true) {
    if (cancelRequested) throw new Error("Cancelled");
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    received += value.byteLength;
    if (total) onProgress?.(received / total);
  }
  await cachePromise;
  const combined = new Uint8Array(received);
  let offset = 0;
  for (const chunk of chunks) {
    combined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  onProgress?.(1);
  await refreshModelStatus();
  return combined;
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
}

function softLimit(x) {
  x = Math.max(-1.5, Math.min(1.5, x));
  return x / (1 + 0.08 * Math.abs(x));
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
  if (/out of memory|allocation|memory/i.test(message)) {
    return "This browser ran out of memory during professional separation. Close other tabs/apps and retry; for long songs, desktop Chrome/Edge is recommended.";
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
