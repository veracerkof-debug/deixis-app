// Демо-приёмник Deixis в браузере. Отвечает страницам пульта и экрана тем же протоколом, что настоящий
// приёмник (core/.../Protocol.kt): pair/hello → paired/welcome, show, mode, video, ping. Файлы — примеры
// из папки media/ и то, что открыто с этого устройства: они остаются в браузере и никуда не отправляются.
(function () {
  const LECTURE = { sha256: "demo-lecture", name: "Город пятнадцати минут.pdf", size: 55408, mime: "application/pdf" };
  const PHOTO = { sha256: "demo-photo", name: "Горы на закате.jpg", size: 82435, mime: "image/jpeg" };
  const VIDEO = { sha256: "demo-video", name: "Демо-видео.mp4", size: 212661, mime: "video/mp4" };

  const REPORT = { sha256: "demo-report", name: "Отчёт по практике.pdf", size: 109603, mime: "application/pdf" };
  const MORNING = { sha256: "demo-morning", name: "Утро в горах.jpg", size: 63551, mime: "image/jpeg" };
  // Документ Word с телефона: страницы нарисовал свой перевод DOCX из ядра (make_assets.py).
  const KONSPEKT = { sha256: "demo-konspekt", name: "Конспект. Город 15 минут.pdf", size: 115408, mime: "application/pdf" };
  // Логотипы примеров «Поверх показа» — те же картинки, что у приложения (core/.../overlay-samples).
  const logo = (n, size) => ({ sha256: "demo-logo-" + n, name: n + ".png", size, mime: "image/png" });
  const LOGO_DEIXIS = logo("deixis", 23406), LOGO_LIVE = logo("live", 20338), LOGO_SPRING = logo("spring", 39160);
  const files = {
    [LECTURE.sha256]: { ref: LECTURE, pages: [0, 1, 2, 3, 4].map((i) => `media/lecture-${i}.jpg`) },
    [REPORT.sha256]: { ref: REPORT, pages: ["media/report-0.jpg"] },
    [PHOTO.sha256]: { ref: PHOTO, url: "media/mountains.jpg" },
    [MORNING.sha256]: { ref: MORNING, url: "media/morning.jpg" },
    [VIDEO.sha256]: { ref: VIDEO, url: "media/clip.mp4", durationMs: 12000 },
    [KONSPEKT.sha256]: { ref: KONSPEKT, pages: ["media/konspekt-0.jpg", "media/konspekt-1.jpg"] },
    [LOGO_DEIXIS.sha256]: { ref: LOGO_DEIXIS, url: "media/deixis.png" },
    [LOGO_LIVE.sha256]: { ref: LOGO_LIVE, url: "media/live.png" },
    [LOGO_SPRING.sha256]: { ref: LOGO_SPRING, url: "media/spring.png" },
  };
  const json = (x) => JSON.stringify(x);

  /**
   * Один демо-экран: своё состояние, свой PIN, свои пульты и зрители. Пульт в браузере (/ws) говорит
   * с ним протоколом приёмника, симулятор приложения — напрямую через [api].
   */
  function createReceiver(key, info, opts = {}) {
    let state = { content: opts.content || null, mode: "NORMAL", video: null, error: null, viewers: opts.viewers || 0, overlays: {} };
    const remotes = new Set();
    const viewers = new Set();
    const listeners = new Set();
    let clock = 0;
    const r = { key, info, pin: opts.pin || "0000", paired: false, address: opts.address || null };

    function viewerState() { return json({ content: state.content, mode: state.mode, video: state.video, overlays: state.overlays, logoImage: state.logoImage || null, now: Date.now() }); }
    function broadcast(event) {
      state.viewers = (opts.viewers || 0) + [...viewers].filter((v) => !v.preview && !v.hall).length;
      for (const p of remotes) if (p.ready) p.send(json({ type: "status", state }));
      for (const v of viewers) v.send(viewerState());
      for (const l of listeners) l(state, event);
    }
    // Ход видео: приёмник сам считает позицию, пульт видит её в подписи «Сейчас».
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
    r.show = (c) => {
      const f = c && c.file && files[c.file.sha256];
      if (c && c.file && !f) return "Файл не найден";
      state = Object.assign({}, state, { content: c, mode: "NORMAL", error: null,
        video: c && c.type === "video" ? { playing: true, positionMs: 0, durationMs: f.durationMs || 0, volume: 100, loop: !!c.loop } : null });
      tickVideo();
      // Настоящий приёмник подтверждает показ, как только кадр готов; здесь — с задержкой сети.
      setTimeout(() => broadcast("show"), 80);
      return null;
    };
    // [logoImage] — своя картинка «Логотипа»; undefined — оставить прежнюю.
    r.mode = (m, logoImage) => { state = Object.assign({}, state, { mode: m }, logoImage !== undefined ? { logoImage } : {}); broadcast("mode"); };
    // Поверх показа: слой не зависит от слайдов, смена слайда его не трогает.
    r.overlays = (o) => { state = Object.assign({}, state, { overlays: o || {} }); broadcast("overlays"); };
    r.video = (action, positionMs, loop, volume) => {
      if (!state.video) return;
      const v = Object.assign({}, state.video);
      if (action === "PLAY") v.playing = true;
      if (action === "PAUSE") v.playing = false;
      if (action === "SEEK") v.positionMs = Math.max(0, Math.min(v.durationMs, positionMs || 0));
      if (action === "LOOP") v.loop = loop == null ? !v.loop : loop;
      if (action === "VOLUME") v.volume = Math.max(0, Math.min(100, Number(volume) || 0));
      state = Object.assign({}, state, { video: v });
      broadcast("video");
    };
    r.pair = (pin) => {
      if (String(pin).trim() !== r.pin) return false;
      r.paired = true;
      broadcast("paired");
      return true;
    };
    r.onChange = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };
    Object.defineProperty(r, "state", { get: () => state });

    r.onRemote = (peer, text) => {
      let m;
      try { m = JSON.parse(text); } catch (e) { return; }
      if (m.type === "ping") return peer.send(json({ type: "pong", t: m.t }));
      if (m.type === "pair" || m.type === "hello") {
        if (m.type === "pair") peer.send(json({ type: "paired", token: "demo-token" }));
        peer.ready = true;
        r.paired = true;
        peer.send(json({ type: "welcome", version: 1, receiver: info, state, viewKey: key }));
        for (const l of listeners) l(state, "welcome");
        return;
      }
      if (!peer.ready) return;
      if (m.type === "show") {
        const err = r.show(m.content);
        if (err) peer.send(json({ type: "error", code: "missing", message: err }));
      } else if (m.type === "mode") r.mode(m.mode, m.logo ? m.logoImage || null : undefined);
      else if (m.type === "video") r.video(m.action, m.positionMs, m.loop, m.volume);
      else if (m.type === "overlays") r.overlays(m.overlays);
    };
    r.addRemote = (peer) => { remotes.add(peer); };
    r.dropRemote = (peer) => { remotes.delete(peer); };
    r.addViewer = (peer) => { viewers.add(peer); peer.send(viewerState()); broadcast(); };
    r.dropViewer = (peer) => { viewers.delete(peer); broadcast(); };
    return r;
  }

  const receivers = {};
  const addReceiver = (key, info, opts) => (receivers[key] = createReceiver(key, info, opts));
  // Экран веб-пульта: на нём уже открыта лекция.
  addReceiver("demo", {
    id: "demo", name: "Зал · демо", platform: "demo", appVersion: "0.6.0", screenWidth: 1920, screenHeight: 1080,
    holyrics: false, convert: false, documents: false,
  }, { content: { type: "pdf", file: LECTURE, page: 0 } });

  // Экраны в сети для приложения: проектор и ноутбук рядом, и сам телефон в режиме «Стать экраном».
  addReceiver("projector", { id: "projector", name: "Проектор, ауд. 214", platform: "android-tv", appVersion: "0.10.9", screenWidth: 1920, screenHeight: 1080,
    holyrics: false, convert: false, documents: true }, { pin: "4827", address: "192.168.1.24:47820", viewers: 6 });
  addReceiver("laptop", { id: "laptop", name: "Ноутбук преподавателя", platform: "desktop-windows", appVersion: "0.10.9", screenWidth: 1920, screenHeight: 1080,
    holyrics: true, convert: true, documents: true }, { pin: "1593", address: "192.168.1.31:47820" });
  addReceiver("phone", { id: "phone", name: "Телефон · Pixel 8", platform: "android-screen", appVersion: "0.10.9" }, { pin: "3071", address: "192.168.1.37:47820" });

  function connect(path, send) {
    const q = new URLSearchParams(path.split("?")[1] || "");
    const rx = receivers[q.get("k") || "demo"] || receivers.demo;
    if (path.startsWith("/ws")) {
      const peer = { send, ready: false };
      peer.receive = (t) => rx.onRemote(peer, t);
      peer.closed = () => rx.dropRemote(peer);
      rx.addRemote(peer);
      return peer;
    }
    if (path.startsWith("/view/ws")) {
      const peer = { send, preview: q.get("preview") === "1", hall: q.get("hall") === "1", receive() {} };
      // Сигнал «жив», как у приёмника: без него страница экрана решит, что связь пропала.
      peer.tick = setInterval(() => send('{"tick":1}'), 2000);
      peer.opened = () => rx.addViewer(peer);
      peer.closed = () => { clearInterval(peer.tick); rx.dropViewer(peer); };
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
    // Вкладка «Библия» веб-пульта: книги, глава и поиск по демо-местам.
    if (path === "/bible/books") return reply(bibleBooks(q.get("module")).map((b) => ({ number: b.number, shortName: b.short, longName: b.long, chapters: b.chapters })));
    if (path === "/bible/chapter") return bibleChapter((q.get("modules") || "").split(","), Number(q.get("book")), Number(q.get("chapter")));
    if (path === "/bible/search") return bibleSearch(q.get("module"), q.get("q") || "");
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
        number: 500, chapterCount: 21,
        names: ["ин", "иоан", "иоанна", "отиоанна", "jn", "jhn", "john"],
        RST: { short: "Ин", long: "От Иоанна", chapter: 3, verseCount: 36, verses: {
          16: "Ибо так возлюбил Бог мир, что отдал Сына Своего Единородного, дабы всякий верующий в Него, не погиб, но имел жизнь вечную.",
          17: "Ибо не послал Бог Сына Своего в мир, чтобы судить мир, но чтобы мир спасен был чрез Него." } },
        KJV: { short: "John", long: "John", chapter: 3, verseCount: 36, verses: {
          16: "For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish, but have everlasting life.",
          17: "For God sent not his Son into the world to condemn the world; but that the world through him might be saved." } },
      },
      psalm: {
        number: 230, chapterCount: 150,
        names: ["пс", "псал", "псалом", "псалтирь", "ps", "psa", "psalm", "psalms"], chapters: [22, 23],
        RST: { short: "Пс", long: "Псалтирь", chapter: 22, verseCount: 6, verses: {
          1: "Господь — Пастырь мой; я ни в чем не буду нуждаться:",
          2: "Он покоит меня на злачных пажитях и водит меня к водам тихим,",
          3: "подкрепляет душу мою, направляет меня на стези правды ради имени Своего.",
          4: "Если я пойду и долиною смертной тени, не убоюсь зла, потому что Ты со мной; Твой жезл и Твой посох — они успокаивают меня." } },
        KJV: { short: "Ps", long: "Psalms", chapter: 23, verseCount: 6, verses: {
          1: "The LORD is my shepherd; I shall not want.",
          2: "He maketh me to lie down in green pastures: he leadeth me beside the still waters.",
          3: "He restoreth my soul: he leadeth me in the paths of righteousness for his name's sake.",
          4: "Yea, though I walk through the valley of the shadow of death, I will fear no evil: for thou art with me; thy rod and thy staff they comfort me." } },
      },
      cor: {
        number: 530, chapterCount: 16,
        names: ["1кор", "1коринфянам", "1екоринфянам", "1co", "1cor", "1corinthians"],
        RST: { short: "1 Кор", long: "1-е Коринфянам", chapter: 13, verseCount: 13, verses: {
          4: "Любовь долготерпит, милосердствует, любовь не завидует, любовь не превозносится, не гордится,",
          5: "не бесчинствует, не ищет своего, не раздражается, не мыслит зла,",
          6: "не радуется неправде, а сорадуется истине;",
          7: "все покрывает, всему верит, всего надеется, все переносит." } },
        KJV: { short: "1 Cor", long: "1 Corinthians", chapter: 13, verseCount: 13, verses: {
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
    // Место номерами (веб-пульт): переводится в адрес основного перевода.
    if (req.book != null && req.chapter != null) {
      const b = Object.values(BIBLE.books).find((x) => x.number === req.book);
      if (!b) return reply(ONLY, 422);
      req = Object.assign({}, req, { ref: `${b.names[0]} ${b[mods[0]].chapter === req.chapter ? b.RST.chapter : req.chapter}${req.from ? ":" + req.from + (req.to ? "-" + req.to : "") : ""}` });
    }
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

  // Книги перевода для выбора места: номер книги MyBible, число глав, глава с текстом в демо и число стихов в ней.
  function bibleBooks(file) {
    const mod = String(file || "").replace(".SQLite3", "");
    return Object.values(BIBLE.books).filter((b) => b[mod]).sort((a, b) => a.number - b.number).map((b) => ({
      number: b.number, names: b.names.slice(), short: b[mod].short, long: b[mod].long, chapters: b.chapterCount,
      chapter: b[mod].chapter, verseCount: b[mod].verseCount, verses: Object.assign({}, b[mod].verses),
    }));
  }

  function bibleChapter(files, number, chapter) {
    const mods = files.map((f) => f.replace(".SQLite3", "")).filter((a) => a === "RST" || a === "KJV");
    const b = Object.values(BIBLE.books).find((x) => x.number === number);
    if (!mods.length || !b || b[mods[0]].chapter !== chapter) return reply(ONLY, 422);
    const verses = (mod) => Object.entries(b[mod].verses).map(([n, text]) => ({ number: Number(n), text }));
    return reply({ book: b.number, chapter, chapters: b.chapterCount, shortName: b[mods[0]].short, longName: b[mods[0]].long, primary: verses(mods[0]), second: mods[1] ? verses(mods[1]) : null });
  }
  function bibleSearch(file, query) {
    const mod = String(file || "").replace(".SQLite3", "");
    const words = query.toLowerCase().replace(/ё/g, "е").split(/[^\p{L}\p{N}]+/u).filter((w) => w.length >= 2);
    const hits = [];
    for (const b of Object.values(BIBLE.books)) {
      const t = b[mod];
      if (!t) continue;
      for (const [n, text] of Object.entries(t.verses)) {
        const norm = text.toLowerCase().replace(/ё/g, "е");
        if (words.length && words.every((w) => norm.includes(w))) hits.push({ book: b.number, chapter: t.chapter, verse: Number(n), text, label: `${t.short} ${t.chapter}:${n}` });
      }
    }
    return reply({ ref: null, hits, more: false });
  }

  function url(path, key) {
    const p = path.split("?")[0];
    let m;
    if ((m = p.match(/^\/view\/page\/([\w-]+)\/(\d+)$/))) { const f = files[m[1]]; return (f && f.pages && f.pages[Number(m[2])]) || ""; }
    if ((m = p.match(/^\/view\/file\/([\w-]+)$/))) { const f = files[m[1]]; return (f && f.url) || ""; }
    return key ? path + (path.includes("?") ? "&" : "?") + "k=" + encodeURIComponent(key) : path;
  }

  window.DeixisDemo = {
    connect, http, url, files, addReceiver, receivers, finishUpload, bibleSlides, bibleModules: () => BIBLE.modules, bibleBooks,
    LECTURE, PHOTO, VIDEO, REPORT, MORNING, KONSPEKT, LOGO_DEIXIS, LOGO_LIVE, LOGO_SPRING,
    // Для витрины веб-пульта: экран «demo».
    onChange(fn) { return receivers.demo.onChange(fn); },
    get state() { return receivers.demo.state; },
  };
})();
