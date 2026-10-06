# Karaoke Studio Web

Responsive desktop/mobile web edition of Karaoke Studio.

## Architecture

- Static front end suitable for Azure Static Web Apps.
- Responsive CSS: two-column desktop workspace and one-column mobile layout.
- YouTube handoff opens Y2Mate in the user's browser for authorized content.
- Upload → Karaoke uses ONNX Runtime Web with HT-Demucs in-browser.
- WebGPU is attempted first; WebAssembly is used as a fallback.
- The AI model is downloaded on first use and cached by the browser when possible.
- Master Audio uses Web Audio API processing.
- Songs are processed locally in the browser; there is no Railway processing dependency.

## Azure Static Web Apps

Use `/web` as the Static Web App `app_location`.

This folder is already static output, so configure:

- app_location: `/web`
- api_location: leave empty
- output_location: leave empty
- skip_app_build: `true`

The Azure deployment token is created when the Static Web App resource is connected to GitHub.
