# Artwork Gallery — Gecko Presence 0.6.6

## Local-first flow

1. In Studio, choose **Pick Large Artwork** or **Pick Small Artwork**. Android's document picker imports the file into Gecko's private gallery.
2. Gecko checks file signature, dimensions, size, storage quota and SHA-256. Supported formats: JPEG, PNG and WebP.
3. To make an image visible to Discord, configure your own Imgur Client-ID in Settings.
4. The default is private/local. A single image can be uploaded after a per-image confirmation, or the user can explicitly enable **Automatically host detected artwork publicly** in Settings. Enabling it requires a confirmation explaining that future detected media covers, selected-app icons and Studio artwork newly chosen by you may be uploaded to Imgur without another prompt.
5. Gecko reuses a saved URL for an identical SHA-256 image. Automatically detected artwork uses a separate bounded cache and temporary private staging files; it does not consume the manual Gallery quota. Manual Gallery uploads store the returned `https://i.imgur.com/...` URL in the private gallery record; detected artwork stores its URL in the separate hash cache.
6. When a Studio image was selected, the URL is inserted into the relevant Studio field automatically. No copying/pasting of image URLs or image IDs is required.

## Limits

- 8 MiB per image.
- 40 gallery images.
- 64 MiB total local gallery storage.
- Maximum 8,192 pixels per dimension and 40 megapixels total.
- No SVG, GIF, HEIC, animated image or arbitrary file upload.

## Privacy

Local images remain app-private. Automatic uploads are disabled until the user enables standing consent. An upload already started cannot be reliably recalled after the HTTP request leaves the device. Uploaded images are public to anyone who obtains their URL; revoking consent prevents future automatic uploads but does not delete already-uploaded Imgur images. The Imgur Client-ID is a public application identifier, not an account password or Client Secret. Gecko does not request Imgur OAuth access.

Deleting a gallery entry removes only the local copy and metadata. It does not delete the remote Imgur image. Gecko uses only `https://api.imgur.com/3/image`; it does not use undocumented Discord image endpoints, Discord-internal attachment references or private Kizzy services.

See [Imgur API documentation](https://apidocs.imgur.com/).
