/* =========================================================================
 * 原生 JS，无框架。封面(bridge)直连 NativeBridge.invoke(method, paramsJson, cbId)。
 * 三条与文档一致的铁律：
 *   - 回调用 window.__nativeCallback(cbId, result) 收，result 是 JSON 对象。
 *   - 进度存百分比(pct)，不存页码；进入时按 pct 恢复。
 *   - 首屏布局测量用 setTimeout（陷阱 ⑧：后台 WebView 里 rAF 会暂停）。
 * ========================================================================= */
(function () {
  'use strict';

  // ---- 偏好（字号/夜间），存 localStorage，独立于单本书进度 ----
  var FS_LEVELS = [15, 18, 22]; // 三档：小/中/大
  var fsIdx = clampInt(localStorage.getItem('fsIdx'), 1, 0, 2);
  var night = localStorage.getItem('night') === '1';
  if (night) document.body.classList.add('night');
  applyFs();

  // ---- 桥：优先原生 JSBridge；纯浏览器预览时降级为本地 mock ----
  var cbSeq = 0;
  var pending = {};
  window.__nativeCallback = function (id, res) {
    var fn = pending[id];
    if (fn) { delete pending[id]; fn(res); }
  };
  // 浏览器降级后端：fetch + localStorage 模拟，便于桌面端直接预览 UI
  var browserBridge = (function () {
    var SEED = ['daodejing.txt', 'lunyu.txt', 'zhuangzi.txt'];
    function ok(o){ o = o || {}; o.ok = true; return o; }
    function fail(r){ return { ok:false, reason:r }; }
    function stripExt(n){ var i=n.lastIndexOf('.'); return i>0?n.slice(0,i):n; }
    function loadP(id){ var v=parseFloat(localStorage.getItem('p_'+id)); return isNaN(v)?0:v; }
    function saveP(id,p){ localStorage.setItem('p_'+id, String(p)); }
    function listBooks(){
      return Promise.all(SEED.map(function(name){
        var head = fetch('seed/'+name,{method:'HEAD'}).then(function(r){
          return parseInt(r.headers.get('content-length')||'0',10);
        }).catch(function(){ return 0; });
        return head.then(function(sz){
          return { id:name, title:stripExt(name), url:'seed/'+encodeURIComponent(name), size:sz, pct:loadP(name) };
        });
      }));
    }
    return {
      invoke: function(method, paramsJson, cbId){
        var p = {}; try { p = JSON.parse(paramsJson)||{}; } catch(e){}
        if (method === 'books.list') {
          listBooks().then(function(books){ __nativeCallback(cbId, ok({books:books})); });
        } else if (method === 'books.import') {
          __nativeCallback(cbId, fail('no_picker'));
        } else if (method === 'books.delete') {
          __nativeCallback(cbId, ok());
        } else if (method === 'progress.save') {
          saveP(p.id, p.pct||0); __nativeCallback(cbId, ok());
        } else if (method === 'progress.load') {
          __nativeCallback(cbId, ok({pct:loadP(p.id)}));
        } else {
          __nativeCallback(cbId, fail('unknown_method'));
        }
      }
    };
  })();
  function bookUrl(id){ return (window.NativeBridge ? '/books/' : 'seed/') + encodeURIComponent(id); }
  var bridge = window.NativeBridge || browserBridge;
  function invoke(method, params) {
    return new Promise(function (resolve) {
      var id = 'cb' + (++cbSeq);
      pending[id] = resolve;
      bridge.invoke(method, JSON.stringify(params || {}), id);
    });
  }

  function toast(msg) {
    var t = document.getElementById('toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.add('show');
    setTimeout(function () { t.classList.remove('show'); }, 1600);
  }
  function clampInt(v, def, lo, hi) {
    v = parseInt(v, 10);
    if (isNaN(v)) return def;
    return Math.max(lo, Math.min(hi, v));
  }
  function fmtSize(b) {
    if (b >= 1048576) return (b / 1048576).toFixed(1) + ' MB';
    if (b >= 1024) return (b / 1024).toFixed(0) + ' KB';
    return b + ' B';
  }
  function applyFs() { document.documentElement.style.setProperty('--fs', FS_LEVELS[fsIdx] + 'px'); }
  function applyNight() {
    document.body.classList.toggle('night', night);
    localStorage.setItem('night', night ? '1' : '0');
  }

  // =====================================================================
  // 路由：index.html = 书架；reader.html?id=xxx = 阅读页
  // =====================================================================
  var page = location.pathname.indexOf('reader.html') >= 0 ? 'reader' : 'index';

  if (page === 'index') {
    runShelf();
  } else {
    runReader();
  }

  // =====================================================================
  // 书架
  // =====================================================================
  function runShelf() {
    var listEl = document.getElementById('list');

    document.getElementById('importBtn').addEventListener('click', function () {
      invoke('books.import').then(function (res) {
        if (res && res.ok && res.book) {
          toast('已导入《' + res.book.title + '》');
          refresh();
        } else if (res && res.reason === 'canceled') {
          // 用户取消，静默
        } else {
          toast('导入失败：' + ((res && res.reason) || '未知'));
        }
      });
    });

    function refresh() {
      invoke('books.list').then(function (res) {
        var books = (res && res.books) || [];
        if (!books.length) return renderEmpty();
        listEl.innerHTML = '';
        books.forEach(function (b) {
          var pct = Math.round((b.pct || 0) * 100);
          var card = document.createElement('div');
          card.className = 'book-card';
          card.innerHTML =
            '<div class="book-cover">' + esc(b.title.slice(0, 1)) + '</div>' +
            '<div class="book-meta">' +
              '<div class="book-title">' + esc(b.title) + '</div>' +
              '<div class="book-sub">' + fmtSize(b.size) +
                (pct > 0 ? ' · 已读 ' + pct + '%' : ' · 未读') + '</div>' +
            '</div>' +
            '<button class="book-del" title="删除">✕</button>';
          card.addEventListener('click', function (e) {
            if (e.target.classList.contains('book-del')) return;
            location.href = 'reader.html?id=' + encodeURIComponent(b.id);
          });
          card.querySelector('.book-del').addEventListener('click', function (e) {
            e.stopPropagation();
            if (confirm('删除《' + b.title + '》？删除后无法恢复。')) {
              invoke('books.delete', { id: b.id }).then(function (r) {
                if (r && r.ok) { toast('已删除'); refresh(); }
                else toast('删除失败');
              });
            }
          });
          listEl.appendChild(card);
        });
      });
    }

    function renderEmpty() {
      listEl.innerHTML =
        '<div class="empty">' +
          '<h2>书架还是空的</h2>' +
          '<p>导入一个 .txt 开始阅读</p>' +
          '<button class="btn" id="emptyImport">＋ 导入图书</button>' +
        '</div>';
      document.getElementById('emptyImport').addEventListener('click', function () {
        document.getElementById('importBtn').click();
      });
    }

    refresh();
  }

  // =====================================================================
  // 阅读页
  // =====================================================================
  function runReader() {
    var id = decodeURIComponent(
      new URLSearchParams(location.search).get('id') || '');
    if (!id) { location.href = 'index.html'; return; }

    var viewport = document.getElementById('viewport');
    var book = document.getElementById('book');
    var loading = document.getElementById('loading');
    var pageEl = document.getElementById('page');
    var titleEl = document.getElementById('rtitle');

    var lastPct = 0;

    document.getElementById('backBtn').addEventListener('click', function () {
      saveProgress();
      location.href = 'index.html';
    });
    document.getElementById('fsBtn').addEventListener('click', function () {
      fsIdx = (fsIdx + 1) % FS_LEVELS.length;
      localStorage.setItem('fsIdx', fsIdx);
      applyFs();
      reflowKeepPos();
      toast('字号 ' + (['小', '中', '大'][fsIdx]));
    });
    document.getElementById('nightBtn').addEventListener('click', function () {
      night = !night; applyNight();
    });

    // 左右半屏点击翻页
    var touchX = null;
    viewport.addEventListener('click', function (e) {
      var w = viewport.clientWidth;
      if (e.clientX < w / 2) gotoPage(curPage() - 1);
      else gotoPage(curPage() + 1);
    });

    // 左右滑动翻页（手机上人本能是滑，不是点）。
    // 因 swipe 必有位移，不会触发 click，与上面 click 互补不双翻。
    var touchStart = null;
    viewport.addEventListener('touchstart', function (e) {
      if (e.touches.length !== 1) { touchStart = null; return; }
      var t = e.touches[0];
      touchStart = { x: t.clientX, y: t.clientY };
    }, { passive: true });
    viewport.addEventListener('touchend', function (e) {
      if (!touchStart) return;
      var t = e.changedTouches[0];
      var dx = t.clientX - touchStart.x;
      var dy = t.clientY - touchStart.y;
      touchStart = null;
      if (Math.abs(dx) > 40 && Math.abs(dy) < 50) {
        // 左滑下一页，右滑上一页
        gotoPage(curPage() + (dx < 0 ? 1 : -1));
      }
    }, { passive: true });

    load();

    function load() {
      invoke('books.list').then(function (res) {
        var books = (res && res.books) || [];
        var meta = null;
        books.forEach(function (b) { if (b.id === id) meta = b; });
        if (meta) titleEl.textContent = meta.title;
      });
      // 正文：直接 fetch 虚拟域名路径，内容永远 UTF-8
      fetch(bookUrl(id))
        .then(function (r) { return r.text(); })
        .then(function (text) {
          render(text);
        })
        .catch(function (e) {
          loading.textContent = '加载失败：' + e;
        });
    }

    function render(text) {
      // 按行切段，空行合并；中文书每行即一段
      var paras = text.split(/\r?\n/).map(function (s) { return s.trim(); });
      var html = '';
      for (var i = 0; i < paras.length; i++) {
        if (paras[i].length === 0) continue;
        html += '<p>' + esc(paras[i]) + '</p>';
      }
      book.innerHTML = html;
      loading.style.display = 'none';

      // 恢复进度：先按上次百分比定位，再重排。用 setTimeout 首屏测量（陷阱 ⑧）
      invoke('progress.load', { id: id }).then(function (res) {
        lastPct = (res && typeof res.pct === 'number') ? res.pct : 0;
        setTimeout(function () {
          layout();
          gotoPct(lastPct);
          updatePage();
        }, 0);
      });
      // 翻页/切字号时刷新页码
      viewport.addEventListener('scroll', updatePage, { passive: true });
    }

    function layout() {
      var avail = viewport.clientWidth - (parseFloat(getComputedStyle(book).paddingLeft) +
                    parseFloat(getComputedStyle(book).paddingRight));
      book.style.columnWidth = avail + 'px';
      book.style.columnGap = '32px';
    }

    function step() {
      var cs = getComputedStyle(book);
      var w = parseFloat(cs.columnWidth);
      var g = parseFloat(cs.columnGap);
      if (!isFinite(w)) w = viewport.clientWidth;       // 兜底，避免 NaN（陷阱 ③相关）
      if (!isFinite(g)) g = 32;
      return w + g;
    }
    function pageCount() {
      var sw = book.scrollWidth;
      return Math.max(1, Math.round(sw / step()));
    }
    function curPage() {
      var s = step();
      if (s <= 0) return 0;
      return Math.round(book.scrollLeft / s);
    }
    function gotoPage(n) {
      n = Math.max(0, Math.min(n, pageCount() - 1));
      book.scrollLeft = n * step();
      updatePage();
      scheduleSave();
    }
    function gotoPct(p) {
      var total = pageCount();
      gotoPage(Math.round((p || 0) * (total - 1)));
    }
    function updatePage() {
      var total = pageCount();
      var cur = curPage() + 1;
      pageEl.textContent = (total ? cur : 0) + ' / ' + total;
    }
    function reflowKeepPos() {
      var savePct = currentPct();
      layout();
      gotoPct(savePct);
      updatePage();
    }
    function currentPct() {
      var s = step();
      var span = book.scrollWidth - s;
      if (span <= 0) return 0;
      return Math.max(0, Math.min(1, book.scrollLeft / span));
    }
    function scheduleSave() {
      // 节流保存
      if (scheduleSave._t) return;
      scheduleSave._t = setTimeout(function () {
        scheduleSave._t = null;
        saveProgress();
      }, 250);
    }
    function saveProgress() {
      var p = currentPct();
      lastPct = p;
      invoke('progress.save', { id: id, pct: p });
    }

    // 离开页面前保存
    window.addEventListener('pagehide', saveProgress);
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'hidden') saveProgress();
    });
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }
})();
