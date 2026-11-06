# DINA TAK GPT
This file includes custom instructions for running TAK-GPT in the DINA TAKtical environment.

## Connect to Gemini on GCP

You need a working TAK Server with Plugin Manager enabled, JDK 17, Google Cloud CLI, locally extracted TAK Server JARs, and a GCP project with billing enabled. Run build commands from the repository root.

### 1. Set up GCP

Run in Bash with an account allowed to enable APIs and manage IAM:

```bash
# Billing must already be enabled.
project_id="YOUR_PROJECT_ID" # Set locally; do not commit the real project ID.
model_project_id="$project_id" # Must match projectID in the deployed bot YAML.
service_account_name="YOUR_SERVICE_ACCOUNT_NAME"
service_account_email="${service_account_name}@${project_id}.iam.gserviceaccount.com"
gcloud auth login
gcloud config set project "$project_id"
gcloud services enable iam.googleapis.com --project="$project_id"
gcloud services enable aiplatform.googleapis.com --project="$model_project_id"

# One-time creation; skip if the account already exists.
gcloud iam service-accounts create "$service_account_name" --display-name="TAK Server" --project="$project_id"

# Allow the service account to call models in the model project.
gcloud projects add-iam-policy-binding "$model_project_id" --member="serviceAccount:${service_account_email}" --role="roles/aiplatform.user" --condition=None
```

TAK Server runs in a hardened Docker container on GCE, with `/opt/tak` inside the container. Attach the service account named by `service_account_email` to the VM with the `cloud-platform` access scope. ADC uses this identity; no JSON key is needed.

### 2. Configure the bot

Use `conf/plugins/tak.server.plugins.TAKChatBotBase.yaml` as the template. Set the real `projectID` in the deployment copy on the VM; keep it out of Git. Edit the group or model there if needed:

```yaml
bots:
  - modelType: "vertex-ai"
    botName: "DINA TAK GPT"
    projectID: "YOUR_PROJECT_ID"
    locationID: "global"
    modelName: "gemini-3.8-flash" # Gemini model API ID.
    groups: ["__ANON__"]        # Default group; confirm users belong to it, or replace/add groups.
    latitude: 0.0
    longitude: 0.0
    historyMessages: 40
    cotTypePrefixes: ["a-", "u-d-"]
```

### 3. Build

Manually copy the JARs from your TAK Server files into `lib/libs/tak/` instead of downloading them from `artifacts.tak.gov`. Follow [GET_JARS.MD](GET_JARS.MD) for extraction and copy commands; TAK artifact repository credentials are not needed.

```bash
# Build the shaded plugin JAR from the repository root.
./gradlew :lib:shadowJar
ls -lh lib/build/libs/*.jar
```

Use libraries matching your server, `5.7-RELEASE-43-HEAD`.

### 4. Deploy to TAK Server

Transfer `lib/build/libs/tak-gpt-all-all.jar` and `conf/plugins/tak.server.plugins.TAKChatBotBase.yaml` to `/opt/tak-server/plugins/` on the VM. Run there:

```bash
# Enter the installed hardened Docker directory and load its version.
cd /opt/tak-server/takserver-docker-hardened-*/
export TAK_VERSION="$(cat tak/version.txt)"

TAK_CONTAINER="takserver-hardened-$TAK_VERSION" # Use the server container name, not the database.

# Copy the staged files into the container.
# First replace YOUR_PROJECT_ID in this VM-only YAML with the real project ID.
nano /opt/tak-server/plugins/tak.server.plugins.TAKChatBotBase.yaml
docker exec "$TAK_CONTAINER" mkdir -p /opt/tak/conf/plugins
docker cp /opt/tak-server/plugins/tak-gpt-all-all.jar "${TAK_CONTAINER}:/opt/tak/lib/"
docker cp /opt/tak-server/plugins/tak.server.plugins.TAKChatBotBase.yaml "${TAK_CONTAINER}:/opt/tak/conf/plugins/"

# Create directories before uncommenting the matching paths in the bot YAML.
docker exec "$TAK_CONTAINER" mkdir -p /opt/tak/data/tak-gpt/reference /opt/tak/data/tak-gpt/attachments /opt/tak/data/tak-gpt/packages

# Restart TAK Server to load the plugin, then inspect logs.
docker restart "$TAK_CONTAINER"
docker logs --follow --tail 100 "$TAK_CONTAINER"
```

Check user group membership in TAK Server admin, then connect a client and chat with **DINA TAK GPT** in Contacts. `__ANON__` is a default group, not a wildcard for every group. The TAK host needs outbound HTTPS access to Google authentication and model APIs.

Uncomment `ragDirectory`, `attachmentDirectory`, `packageDirectory` and `packageGroup` in the deployment YAML to enable document search and package building. Allow the TAK runtime account to read references/attachments and write packages. Supply only documents approved for every user of this bot; restart after changing reference files.

For transfers, configure the client-reachable `packageServerUrl`, `packageKeyStore` and `packageTrustStore`. Supply PKCS12 stores and a dedicated **non-admin** client certificate with IN/OUT access only to `packageGroup`. Set `TAK_GPT_KEYSTORE_PASSWORD` and `TAK_GPT_TRUSTSTORE_PASSWORD` in the plugin process environment through your container configuration, then recreate/restart it. Keep secrets outside Git. Allow HTTPS to the endpoint with a matching hostname and trusted certificate.

Connect directly to this server. Use `/reset` to clear history; it also clears on reconnect/restart or after one hour idle. Try “Create a friendly ground marker named Alpha at 34.1, -118.2”, “List recent CoT events”, “Search reference documents for evacuation procedures”, or “Build a package containing briefing.pdf and send it to me”. Downloads require a SHA-256 hash. Transfers notify the requesting user; receipt is not confirmed. Remove old ZIPs when storage reaches 20 packages per caller scope or 200 total. Each package allows 100 contents and 25 MiB.

References: [Google authentication](https://cloud.google.com/docs/authentication/application-default-credentials), [model setup](https://cloud.google.com/vertex-ai/generative-ai/docs/start/quickstarts/quickstart-multimodal).

