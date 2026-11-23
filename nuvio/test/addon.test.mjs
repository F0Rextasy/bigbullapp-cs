import assert from "node:assert/strict";
import { block, probeStream } from "../addon-live/build.mjs";
import { resolvePosterUrl, shouldRewriteGlobalOutputs } from "../addon/build.mjs";

// 1. addon/build.mjs: poster resolution
assert.equal(resolvePosterUrl("/uploads/poster.webp", "https://www.hdfilmcehennemi2.biz/filmler/"), "https://www.hdfilmcehennemi2.biz/uploads/poster.webp");
assert.equal(resolvePosterUrl("//cdn.example.com/a.jpg", "https://site.tld/"), "https://cdn.example.com/a.jpg");
assert.equal(resolvePosterUrl("https://site.tld/b.jpg", "https://site.tld/"), "https://site.tld/b.jpg");
assert.equal(resolvePosterUrl("data:image/gif;base64,R0lGOD==", "https://site.tld/"), undefined);

// 2. addon/build.mjs: single-id guard protects unrelated catalogs & grp-*
assert.equal(shouldRewriteGlobalOutputs([]), true);
assert.equal(shouldRewriteGlobalOutputs(["dizibox"]), false);

// 3. addon-live/build.mjs: balanced-parenthesis Kotlin block() parser
const ktSample = `
        val STATIC = listOf(
            ch("Kanal (HD)", "General", "https://cdn.example.com/live.m3u8?a=(1)&b=2"),
            // comment with stray ) closing paren
            /* block comment with ) and "(" */
            ch("Tırnaklı \\") Kanal", "News", """https://raw.test/)foo"""),
            ch("Son Kanal", "Sports", "https://cdn.example.com/s.m3u8")
        )
        val AFTER = listOf("should_not_appear")
`;
const parsed = block(ktSample, "val STATIC = listOf(");
assert.ok(parsed.includes("Son Kanal"), "nested parens and comments/strings must not truncate STATIC block early");
assert.ok(!parsed.includes("AFTER"), "block must terminate at matching outer parenthesis");

// 4. addon-live/build.mjs: HTTP 451 geoBlocked retention vs real 404/200 M3U8
const realFetch = globalThis.fetch;
try {
  globalThis.fetch = async () => ({
    status: 451,
    headers: new Headers(),
    body: { cancel: async () => {} },
  });
  const res451 = await probeStream("https://tr-only.example.com/live.m3u8");
  assert.equal(res451.ok, true);
  assert.equal(res451.geoBlocked, true);

  globalThis.fetch = async () => ({
    status: 404,
    headers: new Headers(),
    body: { cancel: async () => {} },
  });
  const res404 = await probeStream("https://dead.example.com/live.m3u8");
  assert.equal(res404.ok, false);

  const enc = new TextEncoder().encode("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhigh.m3u8\n");
  let readDone = false;
  globalThis.fetch = async () => ({
    status: 200,
    headers: new Headers({ "content-type": "application/vnd.apple.mpegurl" }),
    body: {
      getReader: () => ({
        read: async () => {
          if (readDone) return { done: true, value: undefined };
          readDone = true;
          return { done: false, value: enc };
        },
        cancel: async () => {},
      }),
    },
  });
  const res200 = await probeStream("https://ok.example.com/master.m3u8");
  assert.equal(res200.ok, true);
  assert.equal(res200.geoBlocked, undefined);
} finally {
  globalThis.fetch = realFetch;
}

console.log("ok addon.test.mjs: all assertions passed");
