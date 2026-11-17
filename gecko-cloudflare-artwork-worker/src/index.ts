export const MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
const UPLOAD_PATH = "/v1/upload";
const OBJECT_PREFIX = "artwork/";
const IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

type ImageMime = "image/jpeg" | "image/png" | "image/webp";
type ImageExtension = "jpg" | "png" | "webp";

interface R2HttpMetadata {
  contentType?: string;
  cacheControl?: string;
}

interface R2BucketBinding {
  put(
    key: string,
    value: ArrayBuffer | ArrayBufferView | ReadableStream<Uint8Array> | string | null,
    options?: {
      httpMetadata?: R2HttpMetadata;
      customMetadata?: Record<string, string>;
    },
  ): Promise<unknown>;
}

export interface Env {
  ARTWORK_BUCKET: R2BucketBinding;
  GECKO_UPLOAD_TOKEN: string;
  PUBLIC_ASSET_BASE_URL: string;
}

interface ImageFormat {
  mime: ImageMime;
  extension: ImageExtension;
}

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function json(payload: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

function imageFormat(bytes: Uint8Array): ImageFormat | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return { mime: "image/jpeg", extension: "jpg" };
  }

  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length >= png.length && png.every((value, index) => bytes[index] === value)) {
    return { mime: "image/png", extension: "png" };
  }

  if (
    bytes.length >= 12 &&
    ascii(bytes, 0, 4) === "RIFF" &&
    ascii(bytes, 8, 4) === "WEBP"
  ) {
    return { mime: "image/webp", extension: "webp" };
  }

  return null;
}

function ascii(bytes: Uint8Array, start: number, length: number): string {
  let value = "";
  for (let index = start; index < start + length; index += 1) {
    value += String.fromCharCode(bytes[index]);
  }
  return value;
}

async function readBoundedBody(request: Request): Promise<Uint8Array> {
  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^\d+$/.test(declaredLength)) {
      throw new HttpError(400, "Invalid Content-Length header.");
    }
    if (Number(declaredLength) > MAX_UPLOAD_BYTES) {
      throw new HttpError(413, "Image exceeds the 8 MiB upload limit.");
    }
  }

  if (!request.body) throw new HttpError(400, "Image body is required.");

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > MAX_UPLOAD_BYTES) {
        await reader.cancel().catch(() => undefined);
        throw new HttpError(413, "Image exceeds the 8 MiB upload limit.");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  if (total === 0) throw new HttpError(400, "Image body is empty.");

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  // Copy into a dedicated ArrayBuffer for Web Crypto compatibility.
  const digestInput = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(digestInput).set(bytes);

  const digest = await crypto.subtle.digest("SHA-256", digestInput);
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, "0")).join("");
}

function validAssetBaseUrl(value: string): URL | null {
  if (!value || value.length > 253 || value !== value.trim()) return null;
  try {
    const url = new URL(value);
    if (
      url.protocol !== "https:" ||
      !url.hostname ||
      url.username ||
      url.password ||
      url.port ||
      url.pathname !== "/" ||
      url.search ||
      url.hash ||
      url.hostname === "localhost" ||
      url.hostname.endsWith(".localhost") ||
      url.hostname.endsWith(".local")
    ) {
      return null;
    }
    return url;
  } catch {
    return null;
  }
}

async function constantTimeTokenMatch(supplied: string, expected: string): Promise<boolean> {
  if (!supplied || !expected || supplied.length > 256 || expected.length < 32 || expected.length > 256) {
    return false;
  }
  const encoder = new TextEncoder();
  const [left, right] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(supplied)),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ]);
  const a = new Uint8Array(left);
  const b = new Uint8Array(right);
  let difference = 0;
  for (let index = 0; index < a.length; index += 1) difference |= a[index] ^ b[index];
  return difference === 0;
}

async function handleUpload(request: Request, env: Env): Promise<Response> {
  const configuredToken = env.GECKO_UPLOAD_TOKEN;
  if (!configuredToken || configuredToken.length < 32) {
    return json({ success: false, error: "Upload service is not configured." }, 503);
  }

  const authorization = request.headers.get("authorization") ?? "";
  const suppliedToken = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (!(await constantTimeTokenMatch(suppliedToken, configuredToken))) {
    return json({ success: false, error: "Unauthorized." }, 401);
  }

  const baseUrl = validAssetBaseUrl(env.PUBLIC_ASSET_BASE_URL);
  if (!baseUrl) {
    return json({ success: false, error: "Public asset domain is not configured." }, 503);
  }

  const declaredMime = (request.headers.get("content-type") ?? "").split(";", 1)[0].trim().toLowerCase();
  if (!["image/jpeg", "image/png", "image/webp"].includes(declaredMime)) {
    throw new HttpError(415, "Only JPEG, PNG and WebP images are supported.");
  }

  const bytes = await readBoundedBody(request);
  const format = imageFormat(bytes);
  if (!format || format.mime !== declaredMime) {
    throw new HttpError(415, "Image signature does not match the declared content type.");
  }

  const digest = await sha256Hex(bytes);
  const key = `${OBJECT_PREFIX}${digest}.${format.extension}`;

  await env.ARTWORK_BUCKET.put(key, bytes, {
    httpMetadata: {
      contentType: format.mime,
      cacheControl: IMMUTABLE_CACHE_CONTROL,
    },
    customMetadata: {
      sha256: digest,
      managedBy: "gecko-presence",
    },
  });

  return json({
    success: true,
    sha256: digest,
    key,
    url: new URL(key, baseUrl).toString(),
    size: bytes.byteLength,
    contentType: format.mime,
  }, 201);
}

export async function handleRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);

  if (url.pathname === "/health" && request.method === "GET") {
    return json({ success: true, service: "gecko-artwork-upload" });
  }

  if (url.pathname !== UPLOAD_PATH) {
    return json({ success: false, error: "Not found." }, 404);
  }

  if (request.method !== "POST") {
    return new Response(JSON.stringify({ success: false, error: "Method not allowed." }), {
      status: 405,
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
        allow: "POST",
        "x-content-type-options": "nosniff",
      },
    });
  }

  try {
    return await handleUpload(request, env);
  } catch (error) {
    if (error instanceof HttpError) {
      return json({ success: false, error: error.message }, error.status);
    }
    // Do not log request headers, credentials, object bodies, or user data.
    return json({ success: false, error: "Artwork storage is temporarily unavailable." }, 503);
  }
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(request, env);
  },
};
