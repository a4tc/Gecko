# Gecko Artwork Upload Worker

Small authenticated upload service for Gecko Presence. It stores image objects in Cloudflare R2 and returns a public HTTPS URL for Discord to fetch. The Android app receives only the upload endpoint, the public asset base URL, and a user-provided upload token; Cloudflare account credentials never belong in the APK.

## Security model

- `GECKO_UPLOAD_TOKEN` is a Cloudflare Worker secret, not a Wrangler `vars` entry and not committed to Git.
- Android sends `Authorization: Bearer <token>` over HTTPS. Gecko should store this value with Android Keystore encryption and never log it.
- Only `POST /v1/upload` is writable. There is no public list or delete route.
- The Worker streams the request through an 8 MiB limit, checks that JPEG/PNG/WebP MIME matches the file signature, and creates its own object key from SHA-256. Client filenames and object paths are never trusted.
- R2 objects receive the verified content type and immutable cache headers. Re-uploading identical bytes writes the same content-addressed object key.
- JSON responses are `no-store`; object URLs are public to anyone who obtains them. Do not upload private or sensitive images.
- The health endpoint reveals only service identity. It never exposes the bucket name, secret, or object listing.

## Cloudflare setup

1. Create an R2 bucket:

   ```bash
   npx wrangler r2 bucket create gecko-artwork
   ```

2. Attach a custom domain to the bucket in Cloudflare Dashboard → R2 → `gecko-artwork` → Settings → Public access → Custom Domains. Use a domain you control, for example `assets.example.com`. R2 custom-domain delivery must be enabled for this domain before images can be viewed publicly.

3. Edit `wrangler.jsonc` and replace `https://assets.example.com` with that exact custom domain. Keep the URL as an origin with no path, query, or fragment. The same value will later be entered into Gecko as its public asset base URL.

4. Install dependencies and create a high-entropy upload token:

   ```bash
   npm install
   openssl rand -hex 32
   ```

   Copy the generated 64-character value. Do not commit it or put it in `wrangler.jsonc`.

5. Store it as a Worker secret:

   ```bash
   npx wrangler secret put GECKO_UPLOAD_TOKEN
   ```

   Paste the generated token into the prompt. Use the exact same value in Gecko's encrypted upload-token setting when that Android integration is installed.

6. Run tests and type checking, then deploy:

   ```bash
   npm test
   npm run typecheck
   npm run deploy
   ```

7. Save the deployed Worker HTTPS URL as Gecko's upload endpoint, using the `/v1/upload` path. The health check is `<worker-url>/health`.

## Local development

Create `.dev.vars` (ignored by Git) with a local-only token:

```dotenv
GECKO_UPLOAD_TOKEN=replace-with-a-local-random-token-at-least-32-characters
```

Use a local test R2 bucket configured by Wrangler, and set `PUBLIC_ASSET_BASE_URL` to the custom domain you intend to publish to. Do not use a production upload token in local development.

## API contract

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

The Android client must validate the returned URL against its configured public asset origin and independently verify that the returned SHA-256 matches the uploaded bytes before caching it.
