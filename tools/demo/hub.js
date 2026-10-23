// Демо-приёмник Deixis в браузере. Отвечает страницам пульта и экрана тем же протоколом, что настоящий
// приёмник (core/.../Protocol.kt): pair/hello → paired/welcome, show, mode, video, ping. Файлы — примеры
// из папки media/ и то, что открыто с этого устройства: они остаются в браузере и никуда не отправляются.
(function () {
  const LECTURE = { sha256: "demo-lecture", name: "Город пятнадцати минут.pdf", size: 55408, mime: "application/pdf" };
  const PHOTO = { sha256: "demo-photo", name: "Горы на закате.jpg", size: 82435, mime: "image/jpeg" };
  const VIDEO = { sha256: "demo-video", name: "Демо-видео.mp4", size: 212661, mime: "video/mp4" };

  const files = {
    [LECTURE.sha256]: { ref: LECTURE, pages: [0, 1, 2, 3, 4].map((i) => `media/lecture-${i}.jpg`) },
    [PHOTO.sha256]: { ref: PHOTO, url: "media/mountains.jpg" },
    [VIDEO.sha256]: { ref: VIDEO, url: "media/clip.mp4", durationMs: 12000 },
  };

  const receiver = {
    id: "demo", name: "Зал · демо", platform: "demo", appVersion: "0.6.0", screenWidth: 1920, screenHeight: 1080,
    holyrics: false, convert: false, documents: false,
  };
  let state = { content: { type: "pdf", file: LECTURE, page: 0 }, mode: "NORMAL", video: null, error: null, viewers: 0 };
  const remotes = new Set();
  const viewers = new Set();
  const listeners = new Set();

  const json = (x) => JSON.stringify(x);
  function viewerState() { return json({ content: state.content, mode: state.mode, video: state.video }); }
  function broadcast() {
    state.viewers = [...viewers].filter((v) => !v.preview).length;
    for (const r of remotes) if (r.ready) r.send(json({ type: "status", state }));
    for (const v of viewers) v.send(viewerState());
    for (const l of listeners) l(state);
  }

  // Ход видео: приёмник сам считает позицию, пульт видит её в подписи «Сейчас».
  let clock = 0;
  function tickVideo() {
    clearInterval(clock);
    if (!state.video) return;
    clock = setInterval(() => {
      const v = state.video;
      if (!v || !v.playing) return;
      let pos = v.positionMs + 1000;
      if (pos >= v.durationMs) pos = v.loop ? 0 : v.durationMs;
      state.video = Object.assign({}, v, { positionMs: pos, playing: pos < v.durationMs || v.loop });
      broadcast();
    }, 1000);
  }

  function onRemote(peer, text) {
    let m;
    try { m = JSON.parse(text); } catch (e) { return; }
    if (m.type === "ping") return peer.send(json({ type: "pong", t: m.t }));
    if (m.type === "pair" || m.type === "hello") {
      if (m.type === "pair") peer.send(json({ type: "paired", token: "demo-token" }));
      peer.ready = true;
      peer.send(json({ type: "welcome", version: 1, receiver, state, viewKey: "demo" }));
      for (const l of listeners) l(state, "welcome");
      return;
    }
    if (!peer.ready) return;
    if (m.type === "show") {
      const c = m.content;
      const f = c && c.file && files[c.file.sha256];
      if (c.file && !f) return peer.send(json({ type: "error", code: "missing", message: "Файл не найден" }));
      state = Object.assign({}, state, { content: c, mode: "NORMAL", error: null,
        video: c.type === "video" ? { playing: true, positionMs: 0, durationMs: f.durationMs || 0, volume: 100, loop: !!c.loop } : null });
      tickVideo();
      // Настоящий приёмник подтверждает показ сразу, как кадр готов; здесь — с небольшой задержкой сети.
      setTimeout(broadcast, 80);
    } else if (m.type === "mode") {
      state = Object.assign({}, state, { mode: m.mode });
      broadcast();
    } else if (m.type === "video" && state.video) {
      const v = Object.assign({}, state.video);
      if (m.action === "PLAY") v.playing = true;
      if (m.action === "PAUSE") v.playing = false;
      if (m.action === "SEEK") v.positionMs = Math.max(0, Math.min(v.durationMs, m.positionMs || 0));
      state = Object.assign({}, state, { video: v });
      broadcast();
    }
  }

  function connect(path, send, close) {
    if (path === "/ws") {
      const peer = { send, ready: false, receive: (t) => onRemote(peer, t), closed: () => remotes.delete(peer) };
      remotes.add(peer);
      return peer;
    }
    if (path.startsWith("/view/ws")) {
      const peer = { send, preview: path.includes("preview=1"), closed: () => { viewers.delete(peer); clearInterval(peer.tick); broadcast(); } };
      peer.receive = () => {};
      peer.opened = () => { send(viewerState()); broadcast(); };
      // Сигнал «жив», как у приёмника: без него страница экрана решит, что связь пропала.
      peer.tick = setInterval(() => send('{"tick":1}'), 2000);
      viewers.add(peer);
      return peer;
    }
    return { receive() {}, closed() {} };
  }

  // ——— HTTP: загрузка своих файлов, страницы PDF, Библия ———
  const reply = (body, status = 200) => Promise.resolve(new Response(typeof body === "string" ? body : json(body),
    { status, headers: { "Content-Type": typeof body === "string" ? "text/plain; charset=utf-8" : "application/json" } }));
  const uploads = {};
  const MIME = { jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", webp: "image/webp", gif: "image/gif", bmp: "image/bmp",
    mp4: "video/mp4", m4v: "video/mp4", mov: "video/quicktime", webm: "video/webm", mp3: "audio/mpeg", m4a: "audio/mp4", wav: "audio/wav", pdf: "application/pdf" };

  async function finishUpload(u) {
    const ext = (u.name.split(".").pop() || "").toLowerCase();
    const mime = MIME[ext] || "application/octet-stream";
    const blob = new Blob(u.chunks, { type: mime });
    let sha;
    try {
      const d = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
      sha = Array.from(new Uint8Array(d), (b) => b.toString(16).padStart(2, "0")).join("");
    } catch (e) { sha = "local-" + Math.random().toString(16).slice(2); }
    const ref = { sha256: sha, name: u.name, size: blob.size, mime };
    const entry = { ref, url: URL.createObjectURL(blob) };
    if (mime === "application/pdf") entry.pages = await pdfPages(blob);
    if (mime.startsWith("video/") || mime.startsWith("audio/")) entry.durationMs = await mediaDuration(entry.url);
    files[sha] = entry;
    return ref;
  }

  // Свой PDF: страницы рисует PDF.js прямо в браузере, по одной картинке на страницу.
  async function pdfPages(blob) {
    const lib = window.pdfjsLib;
    if (!lib) return null;
    const doc = await lib.getDocument({ data: new Uint8Array(await blob.arrayBuffer()) }).promise;
    const out = [];
    for (let i = 1; i <= Math.min(doc.numPages, 60); i++) {
      const page = await doc.getPage(i);
      const vp0 = page.getViewport({ scale: 1 });
      const vp = page.getViewport({ scale: Math.min(3, 1920 / vp0.width) });
      const canvas = document.createElement("canvas");
      canvas.width = Math.round(vp.width); canvas.height = Math.round(vp.height);
      await page.render({ canvasContext: canvas.getContext("2d"), viewport: vp }).promise;
      const b = await new Promise((res) => canvas.toBlob(res, "image/jpeg", 0.88));
      out.push(URL.createObjectURL(b));
    }
    return out;
  }
  function mediaDuration(url) {
    return new Promise((res) => {
      const v = document.createElement("video");
      v.preload = "metadata";
      v.onloadedmetadata = () => res(Number.isFinite(v.duration) ? Math.round(v.duration * 1000) : 0);
      v.onerror = () => res(0);
      v.src = url;
    });
  }

  async function http(url, opts) {
    const [path, query] = url.split("?");
    const method = (opts.method || "GET").toUpperCase();
    const q = new URLSearchParams(query || "");
    let m;
    if ((m = path.match(/^\/upload\/([\w-]+)$/))) {
      const u = uploads[m[1]] || (uploads[m[1]] = { chunks: [], bytes: 0 });
      if (method === "GET") return reply({ bytes: u.bytes });
      const h = opts.headers || {};
      u.size = Number(h["Deixis-Size"]);
      u.name = decodeURIComponent(h["Deixis-Name"] || "file");
      if (Number(q.get("offset")) !== u.bytes) return reply("Не та часть файла", 409);
      u.chunks.push(opts.body);
      u.bytes += opts.body.size;
      if (u.bytes < u.size) return reply({ bytes: u.bytes });
      delete uploads[m[1]];
      return reply({ file: await finishUpload(u) });
    }
    if ((m = path.match(/^\/pdf\/([\w-]+)\/pages$/))) {
      const f = files[m[1]];
      if (!f || !f.pages) return reply("В демо свой PDF открывается, только когда загрузилась библиотека PDF.js", 501);
      return reply({ pages: f.pages.length });
    }
    if (path === "/bible") return reply(BIBLE.modules);
    if (path === "/bible/slides") return bibleSlides(JSON.parse(opts.body));
    if (path.startsWith("/bible")) return reply("В демо модули Библии не добавляются: на настоящем экране это файл MyBible", 501);
    if (path.startsWith("/holyrics")) return reply("Holyrics в демо не подключён: он работает с Holyrics на компьютере в той же сети", 501);
    if (path === "/convert") return reply("В демо PPTX не переводится: это делает Deixis для компьютера", 501);
    return reply("Нет в демо", 404);
  }

  // ——— Библия: несколько мест в Синодальном переводе и King James Version (оба — общественное достояние) ———
  const BIBLE = {
    modules: [
      { file: "RST.SQLite3", title: "Синодальный перевод", abbreviation: "RST", language: "ru" },
      { file: "KJV.SQLite3", title: "King James Version", abbreviation: "KJV", language: "en" },
    ],
    books: {
      john: {
        names: ["ин", "иоан", "иоанна", "отиоанна", "jn", "jhn", "john"],
        RST: { short: "Ин", long: "От Иоанна", chapter: 3, verses: {
          16: "Ибо так возлюбил Бог мир, что отдал Сына Своего Единородного, дабы всякий верующий в Него, не погиб, но имел жизнь вечную.",
          17: "Ибо не послал Бог Сына Своего в мир, чтобы судить мир, но чтобы мир спасен был чрез Него." } },
        KJV: { short: "John", long: "John", chapter: 3, verses: {
          16: "For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish, but have everlasting life.",
          17: "For God sent not his Son into the world to condemn the world; but that the world through him might be saved." } },
      },
      psalm: {
        names: ["пс", "псал", "псалом", "псалтирь", "ps", "psa", "psalm", "psalms"], chapters: [22, 23],
        RST: { short: "Пс", long: "Псалтирь", chapter: 22, verses: {
          1: "Господь — Пастырь мой; я ни в чем не буду нуждаться:",
          2: "Он покоит меня на злачных пажитях и водит меня к водам тихим,",
          3: "подкрепляет душу мою, направляет меня на стези правды ради имени Своего.",
          4: "Если я пойду и долиною смертной тени, не убоюсь зла, потому что Ты со мной; Твой жезл и Твой посох — они успокаивают меня." } },
        KJV: { short: "Ps", long: "Psalms", chapter: 23, verses: {
          1: "The LORD is my shepherd; I shall not want.",
          2: "He maketh me to lie down in green pastures: he leadeth me beside the still waters.",
          3: "He restoreth my soul: he leadeth me in the paths of righteousness for his name's sake.",
          4: "Yea, though I walk through the valley of the shadow of death, I will fear no evil: for thou art with me; thy rod and thy staff they comfort me." } },
      },
      cor: {
        names: ["1кор", "1коринфянам", "1екоринфянам", "1co", "1cor", "1corinthians"],
        RST: { short: "1 Кор", long: "1-е Коринфянам", chapter: 13, verses: {
          4: "Любовь долготерпит, милосердствует, любовь не завидует, любовь не превозносится, не гордится,",
          5: "не бесчинствует, не ищет своего, не раздражается, не мыслит зла,",
          6: "не радуется неправде, а сорадуется истине;",
          7: "все покрывает, всему верит, всего надеется, все переносит." } },
        KJV: { short: "1 Cor", long: "1 Corinthians", chapter: 13, verses: {
          4: "Charity suffereth long, and is kind; charity envieth not; charity vaunteth not itself, is not puffed up,",
          5: "Doth not behave itself unseemly, seeketh not her own, is not easily provoked, thinketh no evil;",
          6: "Rejoiceth not in iniquity, but rejoiceth in the truth;",
          7: "Beareth all things, believeth all things, hopeth all things, endureth all things." } },
      },
    },
  };
  const ONLY = "В демо есть Ин 3:16–17, Пс 22:1–4 и 1 Кор 13:4–7";

  function bibleSlides(req) {
    const mods = (req.modules || []).map((f) => f.replace(".SQLite3", "")).filter((a) => a === "RST" || a === "KJV");
    if (!mods.length) return reply("Выберите перевод", 422);
    const m = String(req.ref || "").trim().match(/^(.+?)\s*(\d+)(?:\s*:\s*(\d+)(?:\s*[-–—]\s*(\d+))?)?$/);
    if (!m) return reply(ONLY, 422);
    const key = m[1].toLowerCase().replace(/[.\s-]/g, "").replace(/ё/g, "е");
    const book = Object.values(BIBLE.books).find((b) => b.names.some((n) => n === key || (key.length > 2 && n.startsWith(key))));
    const chapter = Number(m[2]);
    if (!book || !(book.chapters || [book.RST.chapter]).includes(chapter)) return reply(ONLY, 422);
    const main = book[mods[0]];
    const all = Object.keys(main.verses).map(Number);
    const from = m[3] ? Number(m[3]) : all[0];
    const to = m[4] ? Number(m[4]) : m[3] ? from : all[all.length - 1];
    const nums = all.filter((n) => n >= from && n <= to);
    if (!nums.length) return reply(ONLY, 422);
    const o = Object.assign({ versesPerSlide: 1, refMode: "ONE_LINE", refPosition: "BOTTOM", fullBookName: false, verseNumbers: true, sideBySide: false }, req.options || {});
    const per = Math.max(1, Math.min(3, o.versesPerSlide));
    const label = (mod, a, b, withModule) => `${o.fullBookName ? book[mod].long : book[mod].short} ${book[mod].chapter}:${a === b ? a : a + "–" + b}${withModule ? " · " + mod : ""}`;
    const slides = [];
    for (let i = 0; i < nums.length; i += per) {
      const g = nums.slice(i, i + per);
      const a = g[0], b = g[g.length - 1];
      slides.push({
        type: "scripture",
        blocks: mods.map((mod) => ({
          text: g.map((n) => (o.verseNumbers && g.length > 1 ? n + " " : "") + book[mod].verses[n]).join(" "),
          ref: o.refMode === "PER_TRANSLATION" ? label(mod, a, b, mods.length > 1) : null,
        })),
        ref: o.refMode === "ONE_LINE" ? label(mods[0], a, b, false) : null,
        refPosition: o.refPosition,
        sideBySide: !!o.sideBySide && mods.length > 1,
        style: Object.assign({}, req.style || {}),
      });
    }
    return reply(slides);
  }

  function url(path) {
    const p = path.split("?")[0];
    let m;
    if ((m = p.match(/^\/view\/page\/([\w-]+)\/(\d+)$/))) { const f = files[m[1]]; return (f && f.pages && f.pages[Number(m[2])]) || ""; }
    if ((m = p.match(/^\/view\/file\/([\w-]+)$/))) { const f = files[m[1]]; return (f && f.url) || ""; }
    return path;
  }

  window.DeixisDemo = {
    connect, http, url, LECTURE, PHOTO, VIDEO,
    onChange(fn) { listeners.add(fn); },
    get state() { return state; },
  };
})();
