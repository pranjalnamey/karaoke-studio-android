#!/usr/bin/env bash
set -euo pipefail

RESOURCE_GROUP="${RESOURCE_GROUP:-karaoke-studio-rg}"
LOCATION="${LOCATION:-centralindia}"
ENVIRONMENT="${ENVIRONMENT:-karaoke-studio-env}"
APP_NAME="${APP_NAME:-karaoke-studio-worker}"
IMAGE="${IMAGE:-ghcr.io/pranjalnamey/karaoke-studio-cloud-worker:latest}"
WEB_ORIGIN="${WEB_ORIGIN:-https://nice-water-0f0dfe300.1.azurestaticapps.net}"

echo "Karaoke Studio - Azure Container Apps setup"
echo "This creates a scale-to-zero cloud worker (2 vCPU / 4 GiB, max 1 replica)."
echo
read -rsp "Choose a private family PIN: " FAMILY_PIN
echo
if [[ -z "${FAMILY_PIN}" ]]; then
  echo "A family PIN is required."
  exit 1
fi

az extension add --name containerapp --upgrade >/dev/null

az group create   --name "${RESOURCE_GROUP}"   --location "${LOCATION}"   --output none

if ! az containerapp env show     --resource-group "${RESOURCE_GROUP}"     --name "${ENVIRONMENT}" >/dev/null 2>&1; then
  az containerapp env create     --resource-group "${RESOURCE_GROUP}"     --name "${ENVIRONMENT}"     --location "${LOCATION}"     --output none
fi

if az containerapp show     --resource-group "${RESOURCE_GROUP}"     --name "${APP_NAME}" >/dev/null 2>&1; then
  az containerapp update     --resource-group "${RESOURCE_GROUP}"     --name "${APP_NAME}"     --image "${IMAGE}"     --cpu 2     --memory 4Gi     --min-replicas 0     --max-replicas 1     --set-env-vars       KARAOKE_ALLOWED_ORIGINS="${WEB_ORIGIN}"       KARAOKE_MAX_UPLOAD_MB=80       KARAOKE_JOB_TTL_SECONDS=3600       KARAOKE_FAMILY_PIN=secretref:family-pin     --secrets family-pin="${FAMILY_PIN}"     --output none
else
  az containerapp create     --resource-group "${RESOURCE_GROUP}"     --name "${APP_NAME}"     --environment "${ENVIRONMENT}"     --image "${IMAGE}"     --ingress external     --target-port 8000     --cpu 2     --memory 4Gi     --min-replicas 0     --max-replicas 1     --secrets family-pin="${FAMILY_PIN}"     --env-vars       KARAOKE_ALLOWED_ORIGINS="${WEB_ORIGIN}"       KARAOKE_MAX_UPLOAD_MB=80       KARAOKE_JOB_TTL_SECONDS=3600       KARAOKE_FAMILY_PIN=secretref:family-pin     --output none
fi

FQDN="$(az containerapp show   --resource-group "${RESOURCE_GROUP}"   --name "${APP_NAME}"   --query properties.configuration.ingress.fqdn   --output tsv)"

echo
echo "Cloud worker created."
echo "Worker URL: https://${FQDN}"
echo
echo "Health test:"
echo "https://${FQDN}/health"
echo
echo "Keep your family PIN private."
echo "Send the Worker URL back to ChatGPT so it can be connected to the Karaoke Studio web app."
