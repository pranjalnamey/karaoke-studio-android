/**
 * Mobile compatibility pathway for browser-only Karaoke Studio.
 *
 * IMPORTANT: The MDX neural model needs its native frame dimension
 * (segmentSize: 256). This module reduces *full-song* memory pressure by
 * feeding short decoded clips to the same model, not by changing its ONNX
 * tensor dimensions. The same model remains loaded across clips.
 */
const RATE = 44100;
const CHANNELS = 2;
const BYTES_PER_FRAME = 4;
const SECTION_SECONDS = 24;
const CONTEXT_SECONDS = 1.5;

function wavHeader(dataBytes, sampleRate = RATE, channels = CHANNELS) {
  if (dataBytes + 36 > 0xffffffff) throw new Error("Output WAV is too large for standard WAV.");
  const header = new Uint8Array(44);
  const v = new DataView(header.buffer);
  const str = (offset, text) => [...text].forEach((char, i) => v.setUint8(offset + i, char.charCodeAt(0)));
  str(0, "RIFF");
  v.setUint32(4, 36 + dataBytes, true);
  str(8, "WAVE");
  str(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, channels, true);
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * channels * 2, true);
  v.setUint16(32, channels * 2, true);
  v.setUint16(34, 16, true);
  str(36, "data");
  v.setUint32(40, dataBytes, true);
  return header;
}

function clipToWav(buffer, start, end) {
  const left = buffer.getChannelData(0);
  const right = buffer.numberOfChannels > 1 ? buffer.getChannelData(1) : left;
  const frames = end - start;
  const pcm = new Uint8Array(frames * BYTES_PER_FRAME);
  const view = new DataView(pcm.buffer);
  for (let i = 0, offset = 0; i < frames; i++) {
    const l = Math.max(-1, Math.min(1, left[start + i]));
    const r = Math.max(-1, Math.min(1, right[start + i]));
    view.setInt16(offset, Math.round(l * 32767), true);
    view.setInt16(offset + 2, Math.round(r * 32767), true);
    offset += BYTES_PER_FRAME;
  }
  return new Blob([wavHeader(pcm.byteLength), pcm], { type: "audio/wav" });
}

function wavePcmData(arrayBuffer) {
  const view = new DataView(arrayBuffer);
  const name = (offset) =>
    String.fromCharCode(view.getUint8(offset), view.getUint8(offset + 1),
      view.getUint8(offset + 2), view.getUint8(offset + 3));
  if (arrayBuffer.byteLength < 44 || name(0) !== "RIFF" || name(8) !== "WAVE") {
    throw new Error("AI returned an invalid WAV file.");
  }

  let format, channelCount, sampleRate, bits;
  let cursor = 12;
  while (cursor + 8 <= arrayBuffer.byteLength) {
    const id = name(cursor);
    const length = view.getUint32(cursor + 4, true);
    const dataStart = cursor + 8;
    if (length > arrayBuffer.byteLength - dataStart) {
      throw new Error("The AI output WAV is incomplete.");
    }
    if (id === "fmt ") {
      if (length < 16) throw new Error("The AI output WAV format is invalid.");
      format = view.getUint16(dataStart, true);
      channelCount = view.getUint16(dataStart + 2, true);
      sampleRate = view.getUint32(dataStart + 4, true);
      bits = view.getUint16(dataStart + 14, true);
    } else if (id === "data") {
      if (format !== 1 || channelCount !== CHANNELS || sampleRate !== RATE || bits !== 16) {
        throw new Error("The AI model returned unexpected audio format.");
      }
      return { pcm: new Uint8Array(arrayBuffer, dataStart, length), frameCount: Math.floor(length / BYTES_PER_FRAME) };
    }
    cursor = dataStart + length + (length & 1);
  }
  throw new Error("The AI output WAV contains no audio data.");
}

/**
 * Full song is decoded once but only approximately 27 seconds are submitted
 * at a time to the separator. 1.5 seconds of extra context at either side
 * reduces section boundary artifacts; context is trimmed from the final WAV.
 * The separator itself retains its normal intra-section overlap.
 */
export async function separateMobileAudio({
  audioBuffer,
  separator,
  cancelled = () => false,
  onProgress = () => {},
}) {
  if (!audioBuffer || audioBuffer.sampleRate !== RATE) {
    throw new Error("Mobile mode requires decoded audio at 44,100 Hz.");
  }
  const frames = audioBuffer.length;
  if (frames === 0) throw new Error("The audio file is empty.");
  const step = Math.round(SECTION_SECONDS * RATE);
  const context = Math.round(CONTEXT_SECONDS * RATE);
  const count = Math.ceil(frames / step);
  const parts = [];

  let currentPart = 0;
  let writtenBytes = 0;
  for (let i = 0; i < count; i++) {
    if (cancelled()) throw new Error("Cancelled");
    currentPart = i;
    const coreStart = i * step;
    const coreEnd = Math.min(frames, coreStart + step);
    const extraStart = Math.max(0, coreStart - context);
    const extraEnd = Math.min(frames, coreEnd + context);
    const inputBlob = clipToWav(audioBuffer, extraStart, extraEnd);
    const inputUrl = URL.createObjectURL(inputBlob);
    let resultUrls = [];

    onProgress(i / count, "Processing section " + (i + 1) + " of " + count + ". Please keep this tab open.");
    try {
      resultUrls = await separator.separate(inputUrl);
      if (cancelled()) throw new Error("Cancelled");
      if (!resultUrls.length) throw new Error("The AI did not return an instrumental track.");

      const response = await fetch(resultUrls[0]);
      if (!response.ok) throw new Error("Could not read the AI-separated audio.");
      const wave = wavePcmData(await response.arrayBuffer());
      const trimBefore = coreStart - extraStart;
      const coreLength = coreEnd - coreStart;
      if (wave.frameCount < trimBefore + coreLength) {
        throw new Error("AI returned fewer audio samples than expected.");
      }
      const offset = trimBefore * BYTES_PER_FRAME;
      // Copy only the useful centre, releasing the context buffers.
      const trimmed = wave.pcm.slice(offset, offset + coreLength * BYTES_PER_FRAME);
      writtenBytes += trimmed.byteLength;
      parts.push(trimmed);
    } finally {
      URL.revokeObjectURL(inputUrl);
      for (const url of resultUrls) URL.revokeObjectURL(url);
    }
    onProgress((i + 1) / count, "Finished section " + (i + 1) + " of " + count + ".");
    // Permit rendering and garbage collection opportunities between sections.
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  if (cancelled()) throw new Error("Cancelled");
  onProgress(1, "Joining " + (currentPart + 1) + " sections into the complete karaoke track.");
  return URL.createObjectURL(
    new Blob([wavHeader(writtenBytes), ...parts], { type: "audio/wav" })
  );
}

// Export pure format functions so the WAV layout can be unit-tested.
export { wavHeader, wavePcmData };
