import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const pluginSource = await readFile(new URL("../index.js", import.meta.url), "utf8");
let runtimeID = 0;

/** Load the Bilibili strategy with isolated host and network stubs. */
async function loadStrategy(fetchMock, prompts) {
  globalThis.host = {
    t: (message) => message,
    signInPrompt: (prompt) => prompts.push(prompt),
    crypto: { md5: () => "signature" },
  };
  globalThis.fetch = fetchMock;
  globalThis.setTimeout = (callback) => {
    queueMicrotask(callback);
    return 0;
  };
  const source = Buffer.from(pluginSource).toString("base64");
  return import(`data:text/javascript;base64,${source}#runtime-${runtimeID++}`);
}

/** Return the minimal Fetch response used by the sign-in API tests. */
function jsonResponse(data) {
  return { ok: true, status: 200, json: async () => data };
}

/** Build a successful login callback containing a session and unrelated query values. */
function loginCallback() {
  const query = new URLSearchParams({
    code: "0",
    gourl: "https://www.bilibili.com/",
    SESSDATA: "session-value",
    DedeUserID: "123",
    DedeUserID__ckMd5: "cookie-check",
    bili_jct: "csrf-value",
    sid: "session-id",
    unrelated: "must-not-be-a-cookie",
  });
  return `https://passport.biligame.com/crossDomain?${query}`;
}

/** Verify scanning, confirmation and successful session validation in order. */
test("QR sign-in reports scan states and validates the returned account", async () => {
  const original = { fetch: globalThis.fetch, host: globalThis.host, setTimeout: globalThis.setTimeout };
  const prompts = [];
  const statuses = [86101, 86090, 0];
  const calls = [];
  try {
    const module = await loadStrategy(async (input, options) => {
      const url = new URL(input);
      calls.push({ url, options });
      if (url.pathname.endsWith("/generate")) {
        return jsonResponse({ code: 0, data: { url: "https://account.bilibili.com/scan", qrcode_key: "qr-key" } });
      }
      if (url.pathname.endsWith("/poll")) {
        assert.equal(url.searchParams.get("qrcode_key"), "qr-key");
        const code = statuses.shift();
        return jsonResponse({ code: 0, data: { code, url: code === 0 ? loginCallback() : "", message: "pending" } });
      }
      assert.equal(url.pathname, "/x/web-interface/nav");
      assert.match(options.headers.Cookie, /SESSDATA=session-value/);
      assert.match(options.headers.Cookie, /bili_jct=csrf-value/);
      assert.doesNotMatch(options.headers.Cookie, /unrelated|gourl|code=/);
      return jsonResponse({ code: 0, data: { isLogin: true, mid: 123, uname: "listener", face: "https://i.example/avatar" } });
    }, prompts);

    const account = await module.signIn();
    assert.deepEqual(account, {
      id: "123",
      name: "listener",
      imageUrl: "https://i.example/avatar",
      page: "personal",
    });
    assert.deepEqual(prompts.map(({ text }) => text), [
      "Scan this QR code with the Bilibili app.",
      "Confirm the login in the Bilibili app.",
    ]);
    assert.equal(calls.filter(({ url }) => url.pathname.endsWith("/poll")).length, 3);
  } finally {
    globalThis.fetch = original.fetch;
    globalThis.host = original.host;
    globalThis.setTimeout = original.setTimeout;
  }
});

/** Verify an expired QR code is replaced before polling continues. */
test("QR sign-in replaces an expired code", async () => {
  const original = { fetch: globalThis.fetch, host: globalThis.host, setTimeout: globalThis.setTimeout };
  const prompts = [];
  let generated = 0;
  let polled = 0;
  try {
    const module = await loadStrategy(async (input) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/generate")) {
        generated += 1;
        return jsonResponse({ code: 0, data: { url: `https://account.bilibili.com/scan/${generated}`, qrcode_key: `key-${generated}` } });
      }
      if (url.pathname.endsWith("/poll")) {
        polled += 1;
        if (polled === 1) {
          assert.equal(url.searchParams.get("qrcode_key"), "key-1");
          return jsonResponse({ code: 0, data: { code: 86038, message: "expired" } });
        }
        assert.equal(url.searchParams.get("qrcode_key"), "key-2");
        return jsonResponse({ code: 0, data: { code: 0, url: loginCallback() } });
      }
      return jsonResponse({ code: 0, data: { isLogin: true, mid: 123, uname: "listener" } });
    }, prompts);

    await module.signIn();
    assert.equal(generated, 2);
    assert.equal(prompts.at(-1).qr, "https://account.bilibili.com/scan/2");
    assert.match(prompts.at(-1).text, /expired/);
  } finally {
    globalThis.fetch = original.fetch;
    globalThis.host = original.host;
    globalThis.setTimeout = original.setTimeout;
  }
});

/** Reject callback URLs that cannot safely supply Bilibili session cookies. */
test("QR sign-in rejects a foreign callback", async () => {
  const original = { fetch: globalThis.fetch, host: globalThis.host, setTimeout: globalThis.setTimeout };
  const prompts = [];
  try {
    const module = await loadStrategy(async (input) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/generate")) {
        return jsonResponse({ code: 0, data: { url: "https://account.bilibili.com/scan", qrcode_key: "qr-key" } });
      }
      return jsonResponse({ code: 0, data: { code: 0, url: "https://example.com/?SESSDATA=x&DedeUserID=1" } });
    }, prompts);
    await assert.rejects(module.signIn(), /unsupported login callback/);
  } finally {
    globalThis.fetch = original.fetch;
    globalThis.host = original.host;
    globalThis.setTimeout = original.setTimeout;
  }
});

/** Verify numeric account IDs and the UP owner's next video page reach the strategy. */
test("UP video pagination accepts numeric IDs and requests the selected page", async () => {
  const original = { fetch: globalThis.fetch, host: globalThis.host, setTimeout: globalThis.setTimeout };
  const prompts = [];
  const calls = [];
  try {
    const module = await loadStrategy(async (input) => {
      const url = new URL(input);
      calls.push(url);
      if (url.pathname.endsWith("/generate")) {
        return jsonResponse({ code: 0, data: { url: "https://account.bilibili.com/scan", qrcode_key: "qr-key" } });
      }
      if (url.pathname.endsWith("/poll")) {
        return jsonResponse({ code: 0, data: { code: 0, url: loginCallback() } });
      }
      if (url.pathname === "/x/web-interface/nav") {
        return jsonResponse({
          code: 0,
          data: {
            isLogin: true,
            mid: 123,
            uname: "listener",
            wbi_img: {
              img_url: `https://i.example/${"a".repeat(32)}.png`,
              sub_url: `https://i.example/${"b".repeat(32)}.png`,
            },
          },
        });
      }
      if (url.pathname === "/x/space/wbi/acc/info") {
        assert.equal(url.searchParams.get("mid"), "42");
        return jsonResponse({ code: 0, data: { name: "UP", face: "https://i.example/up", sign: "profile" } });
      }
      if (url.pathname === "/x/relation/stat") {
        return jsonResponse({ code: 0, data: { follower: 8 } });
      }
      if (url.pathname === "/x/space/wbi/arc/search") {
        assert.equal(url.searchParams.get("mid"), "42");
        assert.equal(url.searchParams.get("pn"), "2");
        return jsonResponse({
          code: 0,
          data: {
            list: { vlist: [{ bvid: "BV1Q9YX6QEn2", title: "Video", duration: 61 }] },
            page: { count: 100 },
          },
        });
      }
      throw new Error(`Unexpected Bilibili request: ${url}`);
    }, prompts);

    await module.signIn();
    const page = await module.browse({ page: "user:42:2" });
    assert.equal(page.title, "UP");
    assert.equal(page.sections[0].tracks[0].id, "BV1Q9YX6QEn2");
    assert.equal(page.sections[0].morePage, "user:42:3");
    assert.ok(calls.some((url) => url.pathname === "/x/space/wbi/arc/search"));
  } finally {
    globalThis.fetch = original.fetch;
    globalThis.host = original.host;
    globalThis.setTimeout = original.setTimeout;
  }
});

/** Verify Bilibili subtitle seconds become player milliseconds. */
test("timed subtitles preserve each line's start and end", async () => {
  const original = { fetch: globalThis.fetch, host: globalThis.host, setTimeout: globalThis.setTimeout };
  const prompts = [];
  try {
    const module = await loadStrategy(async (input) => {
      const url = new URL(input);
      if (url.pathname === "/x/web-interface/view") {
        return jsonResponse({ code: 0, data: { aid: 7, cid: 8 } });
      }
      if (url.pathname === "/x/web-interface/nav") {
        return jsonResponse({
          code: -101,
          data: {
            wbi_img: {
              img_url: `https://i.example/${"a".repeat(32)}.png`,
              sub_url: `https://i.example/${"b".repeat(32)}.png`,
            },
          },
        });
      }
      if (url.pathname === "/x/player/wbi/v2") {
        assert.equal(url.searchParams.get("aid"), "7");
        assert.equal(url.searchParams.get("cid"), "8");
        return jsonResponse({
          code: 0,
          data: {
            subtitle: {
              subtitles: [{ lan: "zh-CN", subtitle_url: "https://aisubtitle.hdslb.com/bfs/ai_subtitle/test.json" }],
            },
          },
        });
      }
      if (url.hostname === "aisubtitle.hdslb.com") {
        return { ok: true, status: 200, json: async () => ({ body: [
          { from: 1.25, to: 2.5, content: "第一句" },
          { from: 3, to: 2, content: "无效时间" },
        ] }) };
      }
      throw new Error(`Unexpected Bilibili request: ${url}`);
    }, prompts);

    const lyrics = await module.lyrics({ id: "BV1Q9YX6QEn2" });
    assert.deepEqual(lyrics.lines, [{ start: 1250, end: 2500, text: "第一句" }]);
  } finally {
    globalThis.fetch = original.fetch;
    globalThis.host = original.host;
    globalThis.setTimeout = original.setTimeout;
  }
});

/** Opening a favorites link must request its selected page instead of calling a missing handler. */
test("favorites browse pages request one page and expose the next page", async () => {
  const original = { fetch: globalThis.fetch, host: globalThis.host, setTimeout: globalThis.setTimeout };
  try {
    const module = await loadStrategy(async (input) => {
      const url = new URL(input);
      assert.equal(url.pathname, "/x/v3/fav/resource/list");
      assert.equal(url.searchParams.get("media_id"), "17");
      assert.equal(url.searchParams.get("pn"), "2");
      assert.equal(url.searchParams.get("ps"), "20");
      return jsonResponse({ code: 0, data: {
        info: { id: 17, title: "收藏夹", media_count: 65 },
        has_more: 1,
        medias: [{ bvid: "BV1Q9YX6QEn2", title: "Video", duration: 61 }],
      } });
    }, []);
    const page = await module.browse({ page: "folder:17:2" });
    assert.equal(page.title, "收藏夹");
    assert.equal(page.sections[0].morePage, "folder:17:3");
    assert.equal(page.sections[0].tracks[0].id, "BV1Q9YX6QEn2");
  } finally {
    globalThis.fetch = original.fetch;
    globalThis.host = original.host;
    globalThis.setTimeout = original.setTimeout;
  }
});

/** Verify audio requests use public playback headers without the login cookie. */
test("audio stream headers do not include the account cookie", async () => {
  const original = { fetch: globalThis.fetch, host: globalThis.host, setTimeout: globalThis.setTimeout };
  const prompts = [];
  try {
    const module = await loadStrategy(async (input, options) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/generate")) {
        return jsonResponse({ code: 0, data: { url: "https://account.bilibili.com/scan", qrcode_key: "qr-key" } });
      }
      if (url.pathname.endsWith("/poll")) {
        return jsonResponse({ code: 0, data: { code: 0, url: loginCallback() } });
      }
      if (url.pathname === "/x/web-interface/nav") {
        assert.match(options.headers.Cookie, /SESSDATA=session-value/);
        return jsonResponse({ code: 0, data: { isLogin: true, mid: 123, uname: "listener" } });
      }
      if (url.pathname === "/x/web-interface/view") {
        assert.match(options.headers.Cookie, /SESSDATA=session-value/);
        return jsonResponse({ code: 0, data: { aid: 7, cid: 8 } });
      }
      if (url.pathname === "/video/BV1Q9YX6QEn2/") {
        assert.match(options.headers.Cookie, /SESSDATA=session-value/);
        const playInfo = { data: { dash: { audio: [{ base_url: "https://upos-sz-mirrorcos.bilivideo.com/audio.m4a", mime_type: "audio/mp4", id: 30280, size: 120 }] } } };
        return { ok: true, status: 200, text: async () => `window.__playinfo__=${JSON.stringify(playInfo)}</script>` };
      }
      throw new Error(`Unexpected Bilibili request: ${url}`);
    }, prompts);

    await module.signIn();
    const stream = await module.stream({ id: "BV1Q9YX6QEn2" });
    assert.equal(stream.url, "https://upos-sz-mirrorcos.bilivideo.com/audio.m4a");
    assert.equal(stream.headers.Cookie, undefined);
    assert.equal(stream._cid, "8");
  } finally {
    globalThis.fetch = original.fetch;
    globalThis.host = original.host;
    globalThis.setTimeout = original.setTimeout;
  }
});
