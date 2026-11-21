# Gecko Artwork Upload Worker

Authenticated upload service for Gecko Presence. Each Gecko user deploys their own Worker and binds their own Cloudflare R2 bucket. The Worker stores verified image bytes and returns a public HTTPS URL for Discord to fetch. Cloudflare account credentials never belong in the Android app.

## Security model

- `GECKO_UPLOAD_TOKEN` is a high-entropy Cloudflare Worker secret, not a Wrangler `vars` entry and not committed to Git.
- Android sends `Authorization: Bearer <token>` over HTTPS. Gecko stores the token as AES-256-GCM ciphertext in private app storage; its non-exportable AES key is held by Android Keystore with an artwork-specific AAD domain.
- The Worker does not provide an upload-token retrieval, object-list, object-read, or object-delete API. `GET /v1/verify` checks the supplied token and returns only the service identifier and configured public asset origin.
- `POST /v1/upload` accepts raw JPEG/PNG/WebP bytes, enforces an 8 MiB cap, verifies the file signature against MIME, and creates its own SHA-256 object key. Client filenames and paths are never trusted.
- R2 objects get a verified content type and immutable cache headers. Re-uploading identical bytes uses the same content-addressed key.
- API JSON responses are `no-store`; R2 URLs are public to anyone who obtains them. Do not upload private or sensitive images.
- `GET /health` is public and returns service identity only. It does not test credentials or expose the bucket name, secret, or object listing.

## Cloudflare setup

1. Create an R2 bucket:

   ```bash
   npx wrangler r2 bucket create gecko-artwork
   ```

2. Attach a custom domain to the bucket in Cloudflare Dashboard → R2 → `gecko-artwork` → Settings → Public access → Custom Domains. Use a domain you control, for example `assets.example.com`. R2 custom-domain delivery must be enabled before images can be viewed publicly.

3. Edit `wrangler.jsonc` and replace `https://assets.example.com` with that exact custom-domain origin. Do not add a path, query, fragment, credentials, or port.

4. Install dependencies and generate a high-entropy token:

   ```bash
   npm install
   openssl rand -hex 32
   ```

   Copy the 64-character value temporarily. Do not commit it or put it in `wrangler.jsonc`.

5. Store the token as a Worker secret:

   ```bash
   npx wrangler secret put GECKO_UPLOAD_TOKEN
   ```

   Paste the generated value into the prompt. Enter the same token into Gecko's **Private Worker upload token** field. Gecko encrypts it locally; the token is never written to source code or BuildConfig.

6. Run tests and type checking, then deploy:

   ```bash
   npm test
   npm run typecheck
   npm run deploy
   ```

7. In Gecko Settings → Artwork Hosting, enter:
   - Worker URL: the deployed Worker origin, such as `https://gecko-artwork-upload.<account-subdomain>.workers.dev`.
   - Public R2 asset domain: the same origin configured in `PUBLIC_ASSET_BASE_URL`, such as `https://assets.example.com`.
   - Private Worker upload token: the exact value stored in `GECKO_UPLOAD_TOKEN`.

   Save the configuration and press **TEST WORKER CONNECTION**. Gecko calls the authenticated `GET /v1/verify` route. The Worker response must match the configured public asset origin before the app reports success.

## Local development

Create `.dev.vars` (ignored by Git) with a local-only token:

```dotenv
GECKO_UPLOAD_TOKEN=replace-with-a-local-random-token-at-least-32-characters
```

Use a local test R2 bucket configured by Wrangler. The Android production client intentionally requires HTTPS and does not accept localhost Worker URLs. Never use a production token in local development.

## API contract

`GET /v1/verify`

Headers:

```http
Authorization: Bearer <upload-token>
```

Success (`200`):

```json
{
  "success": true,
  "service": "gecko-artwork-upload",
  "assetBaseUrl": "https://assets.example.com"
}
```

The token is never returned in the response.

`POST /v1/upload`

Headers:

```http
Authorization: Bearer <upload-token>
Content-Type: image/jpeg | image/png | image/webp
```

Body: raw image bytes (not multipart form data), maximum 8 MiB.

Success (`201`):

```json
{
  "success": true,
  "sha256": "<64 lowercase hex characters>",
  "key": "artwork/<sha256>.<jpg|png|webp>",
  "url": "https://assets.example.com/artwork/<sha256>.<jpg|png|webp>",
  "size": 12345,
  "contentType": "image/png"
}
```

Gecko verifies the returned hash, size, MIME, exact configured asset origin, and content-addressed path before saving or publishing the URL.

## Token rotation and removal

To rotate the token, generate a new value, update the Worker secret with `npx wrangler secret put GECKO_UPLOAD_TOKEN`, then enter the same new value in Gecko Settings and save. The old token should be considered revoked once Cloudflare replaces the secret. To remove the service, delete the Worker secret and disable or remove the Worker. Removing the configuration in Gecko deletes only the local ciphertext and URLs/settings; it does not delete already-uploaded R2 objects. This Worker intentionally has no public delete endpoint.
