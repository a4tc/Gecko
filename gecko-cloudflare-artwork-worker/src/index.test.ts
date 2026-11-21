import { describe, expect, it, vi } from "vitest";
import { handleRequest, MAX_UPLOAD_BYTES, type Env } from "./index";

const TOKEN = "a".repeat(64);
const ASSET_BASE = "https://assets.example.com";

function pngBytes(extraBytes = 0): Uint8Array {
  const signature = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const bytes = new Uint8Array(signature.length + extraBytes);
  bytes.set(signature);
  for (let index = signature.length; index < bytes.length; index += 1) bytes[index] = index % 251;
  return bytes;
}

function makeEnv() {
  const put = vi.fn(async (_key: string, _value: ArrayBuffer | ArrayBufferView | ReadableStream<Uint8Array> | string | null, _options?: unknown) => ({}));
  const env = {
    GECKO_UPLOAD_TOKEN: TOKEN,
    PUBLIC_ASSET_BASE_URL: ASSET_BASE,
    ARTWORK_BUCKET: { put },
  } as unknown as Env;
  return { env, put };
}

describe("Gecko artwork Worker", () => {
  it("stores a verified PNG under its SHA-256 content key", async () => {
    const { env, put } = makeEnv();
    const bytes = pngBytes(16);
    const response = await handleRequest(new Request("https://worker.example/v1/upload", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "image/png" },
      body: bytes,
    }), env);
    const data = await response.json() as { success: boolean; sha256: string; key: string; url: string; size: number };

    expect(response.status).toBe(201);
    expect(data.success).toBe(true);
    expect(data.sha256).toBe("f7f920c005e0b957def68400fa236ef5160460f5ee8a00b7d5ed4ac21b015d27");
    expect(data.key).toBe(`artwork/${data.sha256}.png`);
    expect(data.url).toBe(`${ASSET_BASE}/${data.key}`);
    expect(data.size).toBe(bytes.length);
    expect(put).toHaveBeenCalledOnce();
    expect(put.mock.calls[0]?.[0]).toBe(data.key);
  });

  it("rejects requests without the upload bearer token", async () => {
    const { env, put } = makeEnv();
    const response = await handleRequest(new Request("https://worker.example/v1/upload", {
      method: "POST",
      headers: { "content-type": "image/png" },
      body: pngBytes(),
    }), env);
    expect(response.status).toBe(401);
    expect(put).not.toHaveBeenCalled();
  });

  it("rejects mismatched MIME and image signatures", async () => {
    const { env, put } = makeEnv();
    const response = await handleRequest(new Request("https://worker.example/v1/upload", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "image/jpeg" },
      body: pngBytes(),
    }), env);
    expect(response.status).toBe(415);
    expect(put).not.toHaveBeenCalled();
  });

  it("rejects oversized uploads before writing to R2", async () => {
    const { env, put } = makeEnv();
    const response = await handleRequest(new Request("https://worker.example/v1/upload", {
      method: "POST",
      headers: {
        authorization: `Bearer ${TOKEN}`,
        "content-type": "image/png",
        "content-length": String(MAX_UPLOAD_BYTES + 1),
      },
      body: pngBytes(),
    }), env);
    expect(response.status).toBe(413);
    expect(put).not.toHaveBeenCalled();
  });

  it("rejects non-HTTPS asset origins", async () => {
    const { env, put } = makeEnv();
    (env as unknown as { PUBLIC_ASSET_BASE_URL: string }).PUBLIC_ASSET_BASE_URL = "http://assets.example.com";
    const response = await handleRequest(new Request("https://worker.example/v1/upload", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "image/png" },
      body: pngBytes(),
    }), env);
    expect(response.status).toBe(503);
    expect(put).not.toHaveBeenCalled();
  });


  it("rejects IP-literal public asset origins", async () => {
    const { env, put } = makeEnv();
    (env as unknown as { PUBLIC_ASSET_BASE_URL: string }).PUBLIC_ASSET_BASE_URL = "https://127.0.0.1";
    const response = await handleRequest(new Request("https://worker.example/v1/verify", {
      headers: { authorization: `Bearer ${TOKEN}` },
    }), env);
    expect(response.status).toBe(503);
    expect(put).not.toHaveBeenCalled();
  });

  it("verifies a configured Worker token and returns only the public asset origin", async () => {
    const { env, put } = makeEnv();
    const response = await handleRequest(new Request("https://worker.example/v1/verify", {
      headers: { authorization: `Bearer ${TOKEN}` },
    }), env);
    const data = await response.json() as Record<string, unknown>;
    expect(response.status).toBe(200);
    expect(data).toEqual({ success: true, service: "gecko-artwork-upload", assetBaseUrl: ASSET_BASE });
    expect(JSON.stringify(data)).not.toContain(TOKEN);
    expect(put).not.toHaveBeenCalled();
  });

  it("rejects an invalid token on the verification endpoint", async () => {
    const { env, put } = makeEnv();
    const response = await handleRequest(new Request("https://worker.example/v1/verify", {
      headers: { authorization: `Bearer ${"b".repeat(64)}` },
    }), env);
    expect(response.status).toBe(401);
    expect(put).not.toHaveBeenCalled();
  });

  it("requires GET for Worker verification", async () => {
    const { env, put } = makeEnv();
    const response = await handleRequest(new Request("https://worker.example/v1/verify", {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}` },
    }), env);
    expect(response.status).toBe(405);
    expect(response.headers.get("allow")).toBe("GET");
    expect(put).not.toHaveBeenCalled();
  });

  it("keeps health checks public without exposing configuration", async () => {
    const { env, put } = makeEnv();
    const response = await handleRequest(new Request("https://worker.example/health"), env);
    const data = await response.json() as Record<string, unknown>;
    expect(response.status).toBe(200);
    expect(data).toEqual({ success: true, service: "gecko-artwork-upload" });
    expect(JSON.stringify(data)).not.toContain(TOKEN);
    expect(put).not.toHaveBeenCalled();
  });
});
