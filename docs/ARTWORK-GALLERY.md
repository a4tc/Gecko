# Artwork Gallery — Gecko Presence 0.6.6

## Local-first flow

1. In Studio, choose **Pick Large Artwork** or **Pick Small Artwork**. Android's document picker imports the file into Gecko's private gallery.
2. Gecko checks file signature, dimensions, size, storage quota and SHA-256. Supported formats: JPEG, PNG and WebP.
3. To publish an image for Discord, configure your own Cloudflare Worker + R2 bucket in Settings → Artwork Hosting. Enter the Worker HTTPS origin, its public R2 asset domain, and the Worker upload token. Gecko stores the token encrypted on the device with Android Keystore.
4. The default remains private/local. Each manual Gallery or Studio upload requires a per-image confirmation. Detected media covers and selected-app icons are uploaded automatically only after the user separately enables standing public-upload consent; this setting is OFF by default.
5. Gecko verifies Worker authentication with `GET /v1/verify`. Each uploaded image is sent as raw bytes to `POST /v1/upload`. The app verifies the response SHA-256, byte count, MIME, exact configured asset host, and content-addressed path before saving the URL.
6. Gecko reuses a saved URL for an identical SHA-256 image. Automatic artwork uses a separate bounded cache and private staging files; it does not consume the 40-item manual Gallery quota. The resulting URL is inserted into the selected Studio field automatically.

## Hosting and privacy

Each user controls their own Cloudflare account, Worker secret and R2 bucket. Gecko never asks for a Cloudflare account password or Cloudflare API token. The private upload token is stored as AES-GCM ciphertext in app-private preferences, with a separate Android Keystore key and artwork-specific authenticated data. The token is sent only to the configured HTTPS Worker and redirects are disabled.

R2 custom-domain URLs are public to anyone who obtains the URL. The Worker has no public object-list, read, or delete API, but public URL delivery is deliberately enabled so Discord can fetch the image. Do not upload private or sensitive images. Disabling consent prevents future automatic uploads; it cannot recall an upload already sent or delete an existing R2 object.

Legacy Imgur URLs already stored in old Gallery records or Studio drafts remain valid and readable. New Android artwork uploads use the configured Cloudflare Worker; the old Imgur URL validator is retained only for backward compatibility.

## Limits

- 8 MiB per image.
- 40 manual Gallery images.
- 64 MiB total local Gallery storage.
- Maximum 8,192 pixels per dimension and 40 megapixels total.
- No SVG, GIF, HEIC, animated image or arbitrary file upload.
- The Worker stores content-addressed JPEG, PNG and WebP objects with an 8 MiB maximum.

## Deletion and token rotation

Deleting a Gallery entry removes only the local copy and its metadata. It does not delete the remote R2 object. The current Worker deliberately exposes no public delete endpoint. Rotate a token by replacing `GECKO_UPLOAD_TOKEN` in Cloudflare and saving the matching new token in Gecko Settings. Removing Worker configuration clears the token ciphertext and local configuration on this device; it does not delete objects from R2.

See [the Worker setup and API guide](../gecko-cloudflare-artwork-worker/README.md).
