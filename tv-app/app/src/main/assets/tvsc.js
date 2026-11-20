// Script được app chèn vào MỌI frame của trang web (kể cả iframe trình phát phim).
// Nhiệm vụ: tìm thẻ <video>, nhận lệnh điều khiển từ app và báo trạng thái về.
// Giao tiếp qua đối tượng `tvscBridge` do WebViewCompat.addWebMessageListener chèn vào.
(function () {
  if (window.__tvscLoaded) return;
  window.__tvscLoaded = true;

  var bridge = window.tvscBridge;
  var FS = 'tvsc-fs';
  var LOCK = 'tvsc-lock';
  var hadVideo = false;
  var styleAdded = false;

  function post(msg) {
    if (bridge) bridge.postMessage(JSON.stringify(msg));
  }

  function addStyle() {
    if (styleAdded) return;
    styleAdded = true;
    var st = document.createElement('style');
    st.textContent =
      '.' + FS + '{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;' +
      'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
      'min-width:0!important;min-height:0!important;margin:0!important;padding:0!important;border:0!important;' +
      'z-index:2147483647!important;background:#000!important;transform:none!important;visibility:visible!important;opacity:1!important}' +
      'video.' + FS + '{object-fit:contain!important}' +
      'html.' + LOCK + ',html.' + LOCK + ' body{overflow:hidden!important}';
    (document.head || document.documentElement).appendChild(st);
  }

  function area(el) {
    var r = el.getBoundingClientRect();
    return Math.max(0, r.width) * Math.max(0, r.height);
  }

  // Video "chính": ưu tiên video đang phát, có thời lượng, rồi tới video to nhất.
  function bestVideo() {
    var vs = document.getElementsByTagName('video');
    var best = null, score = -1;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      var a = area(v);
      if (a < 2000 && v.paused) continue; // bỏ qua video tí hon (quảng cáo ẩn, preview)
      var s = a + (v.paused ? 0 : 1e9) + (v.duration > 0 ? 1e8 : 0);
      if (s > score) { score = s; best = v; }
    }
    return best;
  }

  // Bấm nút Play lớn của các trình phát phổ biến khi chưa có <video> nào chạy.
  var PLAY_SELECTORS = [
    '.jw-display-icon-display', '.jw-icon-display', '.vjs-big-play-button', '.plyr__control--overlaid',
    '.fp-play', '.dplayer-mobile-play', '.art-control-play', '.art-state', '.play-button', '.btn-play',
    '.playbtn', '[class*="play-btn"]', '[class*="btn-play"]', '[class*="big-play"]',
    '[aria-label="Play"]', '[aria-label="Phát"]', '[title="Play"]'
  ];

  function clickPlayButton() {
    for (var i = 0; i < PLAY_SELECTORS.length; i++) {
      var els = document.querySelectorAll(PLAY_SELECTORS[i]);
      for (var j = 0; j < els.length; j++) {
        if (area(els[j]) > 0) { els[j].click(); return true; }
      }
    }
    return false;
  }

  function play(v) {
    if (v.ended) v.currentTime = 0;
    var p = v.play();
    if (p && p.catch) p.catch(function () {});
  }

  function setVideoFullscreen(on) {
    addStyle();
    var v = bestVideo();
    var all = document.querySelectorAll('.' + FS);
    for (var i = 0; i < all.length; i++) all[i].classList.remove(FS);
    document.documentElement.classList.toggle(LOCK, !!on && !!v);
    if (on && v) {
      v.classList.add(FS);
      v.controls = false;
    }
  }

  // Ở các frame cha (trang chính, iframe trung gian): phóng to iframe chứa trình phát.
  function setFrameFullscreen(on, origin) {
    addStyle();
    var all = document.querySelectorAll('iframe.' + FS);
    for (var i = 0; i < all.length; i++) all[i].classList.remove(FS);
    document.documentElement.classList.remove(LOCK);
    if (!on) return;
    var frames = document.getElementsByTagName('iframe');
    var best = null, score = 0;
    for (var k = 0; k < frames.length; k++) {
      var f = frames[k];
      var a = area(f);
      if (a < 10000) continue;
      var src = f.src || '';
      var s = a + (origin && src.indexOf(origin) === 0 ? 1e9 : 0);
      if (s > score) { score = s; best = f; }
    }
    if (best) {
      best.classList.add(FS);
      document.documentElement.classList.add(LOCK);
    }
  }

  function insertText(text) {
    var el = document.activeElement;
    if (!el || !document.hasFocus()) return false;
    var editable = el.isContentEditable || el.tagName === 'TEXTAREA' ||
      (el.tagName === 'INPUT' && !/^(button|checkbox|radio|submit|reset|file|image|range|color)$/i.test(el.type));
    if (!editable) return false;
    if (!document.execCommand('insertText', false, text)) {
      el.value = (el.value || '') + text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return true;
  }

  function handle(cmd) {
    var v = bestVideo();
    switch (cmd.type) {
      case 'play':
      case 'resume':
        if (v) play(v); else clickPlayButton();
        break;
      case 'pause':
        if (v) v.pause();
        break;
      case 'toggle':
        if (v) { if (v.paused || v.ended) play(v); else v.pause(); } else clickPlayButton();
        break;
      case 'seek':
        if (v && isFinite(cmd.position)) v.currentTime = Math.max(0, cmd.position);
        break;
      case 'seekBy':
        if (v && isFinite(cmd.delta)) v.currentTime = Math.max(0, v.currentTime + cmd.delta);
        break;
      case 'autoplay':
        if (v) play(v); else clickPlayButton();
        break;
      case 'fs':
        setVideoFullscreen(cmd.on);
        break;
      case 'fsFrame':
        setFrameFullscreen(cmd.on, cmd.origin);
        break;
      case 'text':
        insertText(cmd.text || '');
        break;
    }
    report();
  }

  function report() {
    var v = bestVideo();
    if (!v) {
      if (hadVideo) post({ type: 'v', has: false });
      hadVideo = false;
      return;
    }
    hadVideo = true;
    var d = v.duration;
    post({
      type: 'v',
      has: true,
      playing: !v.paused && !v.ended && v.readyState > 2,
      waiting: !v.paused && v.readyState <= 2,
      ended: v.ended,
      pos: v.currentTime || 0,
      dur: isFinite(d) ? d : 0,
      live: d === Infinity,
      area: area(v),
      fs: v.classList.contains(FS)
    });
  }

  // Cho phép app gọi trực tiếp qua evaluateJavascript khi WebView quá cũ.
  window.__tvscHandle = handle;

  if (bridge) {
    bridge.onmessage = function (ev) {
      try { handle(JSON.parse(ev.data)); } catch (e) {}
    };
    post({ type: 'hello' });
  }

  setInterval(report, 1000);
})();
