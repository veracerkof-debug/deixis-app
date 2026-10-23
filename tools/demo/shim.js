// Демо Deixis: эта вставка идёт первой в копиях remote.html и view.html. Вместо сети страницы говорят
// с демо-приёмником (hub.js) в родительском окне — тем же протоколом, что с настоящим экраном.
(function () {
  function findHub() {
    let w = window;
    for (let i = 0; i < 5; i++) {
      try { if (w.DeixisDemo) return w.DeixisDemo; } catch (e) { return null; }
      if (w.parent === w) return null;
      w = w.parent;
    }
    return null;
  }
  // Демо работает только внутри витрины: открытая отдельно страница остаётся как есть.
  let sameOrigin = false;
  try { sameOrigin = window.parent !== window && !!window.parent.document; } catch (e) {}
  if (!sameOrigin) return;
  // Витрина может ещё грузиться: приёмник ищется в момент подключения, а не при старте страницы.
  const whenHub = () => new Promise((res) => {
    const t0 = Date.now();
    (function look() { const h = findHub(); if (h || Date.now() - t0 > 10000) res(h); else setTimeout(look, 50); })();
  });

  // WebSocket: те же события и send(), только сообщения ходят внутри страницы.
  class DemoSocket {
    constructor(url) {
      this.url = url;
      this.readyState = 0;
      this.onopen = this.onmessage = this.onclose = this.onerror = null;
      const path = url.replace(/^wss?:\/\/[^/]+/, "");
      whenHub().then((hub) => {
        if (this.readyState !== 0) return;
        if (!hub) return this.close(1006);
        this.readyState = 1;
        this.peer = hub.connect(path, (data) => { if (this.readyState === 1 && this.onmessage) this.onmessage({ data }); }, () => this.close(1000));
        if (this.onopen) this.onopen({});
        if (this.peer && this.peer.opened) this.peer.opened();
      });
    }
    send(data) { if (this.readyState === 1 && this.peer) this.peer.receive(data); }
    close(code) {
      if (this.readyState === 3) return;
      this.readyState = 3;
      if (this.peer) this.peer.closed();
      if (this.onclose) setTimeout(() => this.onclose({ code: code || 1000 }), 0);
    }
  }
  DemoSocket.CONNECTING = 0; DemoSocket.OPEN = 1; DemoSocket.CLOSING = 2; DemoSocket.CLOSED = 3;
  window.WebSocket = DemoSocket;

  const realFetch = window.fetch.bind(window);
  window.fetch = (input, opts) => {
    const url = typeof input === "string" ? input : input.url;
    if (/^(https?:|blob:|data:)/.test(url) || !url.startsWith("/")) return realFetch(input, opts);
    return whenHub().then((hub) => hub ? hub.http(url, opts || {}) : Promise.reject(new TypeError("Демо-приёмник не найден")));
  };

  // Адрес страницы в демо не меняется: иначе перезагрузка открыла бы несуществующий путь.
  history.replaceState = () => {};
  window.DeixisDemoUrl = (path) => { const h = findHub(); return h ? h.url(path) : path; };
})();
