# Artwork Gallery — Gecko Presence 0.6.6

## Local-first flow

1. In Studio, choose **Pick Large Artwork** or **Pick Small Artwork**.
2. Android's document picker opens. Gecko copies the chosen file into its private gallery.
3. Gecko checks file signature, dimensions, size, storage quota, and SHA-256. Supported formats: JPEG, PNG, WebP.
4. To use the image in Discord Rich Presence, configure your own Imgur Client-ID in Settings.
5. Gecko asks for confirmation before upload. Confirming sends the selected image to Imgur's anonymous image endpoint.
6. The returned `https://i.imgur.com/...` URL is inserted into the selected Studio field and saved to the local gallery record.

## Limits

- 8 MiB per image.
- 40 gallery images.
- 64 MiB total local gallery storage.
- Maximum 8,192 pixels per dimension and 40 megapixels total.
- No SVG, GIF, HEIC, animated image, or arbitrary file upload.

## Privacy

Local images remain app-private and are not uploaded automatically. An upload is public to anyone who obtains its URL. The Imgur Client-ID is a public application identifier, not an Imgur account password or Client Secret. Gecko does not request Imgur OAuth access. Deleting a gallery entry deletes only the local copy; it does not delete the remote Imgur image.

Imgur documents anonymous API uploads using `Authorization: Client-ID {client_id}` after registering an application. See [Imgur API documentation](https://apidocs.imgur.com/).
