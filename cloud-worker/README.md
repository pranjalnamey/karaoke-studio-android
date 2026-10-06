# Karaoke Studio Cloud Worker

This is the optional middle-tier processor for Karaoke Studio.

It exists for phones that cannot run UVR Karaoke 2 reliably in the browser. The phone uploads the already-authorized audio file, this worker creates:

- Original MP3 at 320 kbps
- Karaoke MP3 at 320 kbps using `UVR_MDXNET_KARA_2.onnx`

The user's PC is not involved.

## Recommended Azure Container Apps settings

Use **Consumption** with:

- CPU: 2 vCPU
- Memory: 4 GiB
- Min replicas: 0
- Max replicas: 1
- External ingress: enabled
- Target port: 8000

Set these environment variables:

- `KARAOKE_FAMILY_PIN` = a private family PIN
- `KARAOKE_ALLOWED_ORIGINS` = `https://nice-water-0f0dfe300.1.azurestaticapps.net`
- `KARAOKE_MAX_UPLOAD_MB` = `80`

The frontend polls the active job, which keeps the worker replica active while processing. Results expire after one hour by default.

## Cost guardrails

Azure Container Apps Consumption can scale to zero when idle. Keep **min replicas = 0** and **max replicas = 1**. The app is designed for low-volume family usage, not public access.

Azure's monthly free grant is shared at subscription level, so this architecture can remain inside the grant for light usage, but it is not an absolute zero-bill guarantee if usage exceeds the grant or other Azure resources are enabled.

## Security

The worker rejects processing requests unless the `X-Karaoke-PIN` header matches `KARAOKE_FAMILY_PIN`. Do not hard-code the PIN into the public web files. The web app stores the PIN only in that browser's local storage after the family member enters it.

## YouTube

The worker intentionally does not automate YouTube downloading. Cloud datacenter IPs are often challenged or blocked by YouTube. Keep the existing browser handoff for media you own or are authorized to process, then upload the downloaded audio to this worker.
