#!/usr/bin/env python3
"""Small stdin/stdout bridge: JSON request -> curl_cffi Chrome-TLS response JSON.

Used only by nuvio/tools/verify-all.mjs as the last-resort fetch fallback when
Node/undici's TLS fingerprint gets a Cloudflare 403 (AGENTS.md egress notes).
"""
import json
import sys

try:
    from curl_cffi import requests as curl_requests
except Exception as exc:  # pragma: no cover - host tooling only
    print(json.dumps({"status": 0, "error": f"curl_cffi missing: {exc}"}))
    raise SystemExit(0)

req = json.load(sys.stdin)
headers = req.get("headers") or {}
try:
    response = curl_requests.request(
        req.get("method") or "GET",
        req["url"],
        headers=headers,
        data=req.get("body"),
        impersonate="chrome",
        timeout=25,
        allow_redirects=True,
    )
    print(json.dumps({
        "status": response.status_code,
        "url": response.url,
        "headers": dict(response.headers),
        "body": response.text,
    }))
except Exception as exc:
    print(json.dumps({"status": 0, "error": str(exc)}))
