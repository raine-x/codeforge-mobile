package com.hxy.codeforge.ui.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * 注入到页面里的调试桥。
 *
 * ## 为什么要有它
 *
 * 内置浏览器要能被 AI 驱动，就得让页面**可被查询**而不是只能看：
 *  - 「布局查看」：列出页面上可交互元素及其矩形、样式、选择器；
 *  - 「元素选择」：人在页面上点一下，壳侧拿到该元素的结构化信息；
 *  - 「截图」：Android 侧把当前可见区域抓成 PNG。
 *
 * ## 安全边界
 *
 * [AndroidBridge] 只暴露一个 [post] 方法，且只认 "element" / "screenshot" /
 * "click" 三种事件 —— 页面能做的最坏的事就是提交一条假的元素描述。
 * 真正敏感的能力（读文件、发请求）都不在这里。
 *
 * `addJavascriptInterface` 只在 http(s) 与 data: 场景挂载，**file:// 一律不挂**。
 */
object PageBridge {

    /**
     * 注入脚本。
     *
     * 约束：**不含 `$`**（Kotlin 原始字符串里 `$` 要转义，模板字符串又必须用），
     * 所以 JS 一律用字符串拼接而不是模板字符串。
     */
    const val SCRIPT: String = """
(function () {
  if (window.__cf) return;
  var P = window.__cf = {};
  var api = window.__cfAndroid;

  function post(kind, payload) {
    try { if (api && api.post) api.post(kind, payload); } catch (e) {}
  }
  function mk(tag, css) {
    var d = document.createElement(tag);
    d.style.cssText = css;
    return d;
  }
  function cssPath(el) {
    if (!el || el.nodeType !== 1) return '';
    var parts = [], node = el, guard = 0;
    while (node && node.nodeType === 1 && guard < 8) {
      guard++;
      var seg = node.tagName.toLowerCase();
      if (node.id) { parts.unshift('#' + node.id); break; }
      var parent = node.parentElement;
      if (parent) {
        var same = [], kids = parent.children;
        for (var i = 0; i < kids.length; i++) if (kids[i].tagName === node.tagName) same.push(kids[i]);
        if (same.length > 1) seg += ':nth-of-type(' + (same.indexOf(node) + 1) + ')';
      }
      parts.unshift(seg);
      node = node.parentElement;
    }
    return parts.join(' > ');
  }
  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return { x: r.left, y: r.top, width: r.width, height: r.height, bottom: r.bottom, right: r.right };
  }
  function styleMap(el) {
    var cs = window.getComputedStyle(el);
    var keys = ['display','position','color','background-color','font-size',
                'font-weight','padding','margin','border-radius','opacity','z-index'];
    var out = {};
    for (var i = 0; i < keys.length; i++) {
      var v = cs.getPropertyValue(keys[i]);
      if (v) out[keys[i]] = String(v).trim();
    }
    return out;
  }
  function describe(el) {
    if (!el || el.nodeType !== 1) return null;
    var txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    return {
      tag: el.tagName ? el.tagName.toLowerCase() : '',
      id: el.id || '',
      cls: (typeof el.className === 'string') ? el.className : '',
      text: txt.slice(0, 200),
      selector: cssPath(el),
      rect: rectOf(el),
      styles: styleMap(el),
      html: el.outerHTML ? el.outerHTML.slice(0, 4000) : '',
      visible: !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)
    };
  }

  // ---- 调试接口 ----

  /** 查单个元素的布局信息。selector 为空则返回 null。 */
  P.inspect = function (selector) {
    try {
      var el = selector ? document.querySelector(selector) : null;
      return JSON.stringify(describe(el));
    } catch (e) { return 'null'; }
  };

  /** 列出页面上所有可交互元素（按钮/链接/输入/选择/可点容器）。 */
  P.list = function () {
    try {
      var sel = 'a[href],button,input,textarea,select,summary,[onclick],[role=button],[role=link],[contenteditable=true]';
      var nodes = document.querySelectorAll(sel);
      var out = [];
      for (var i = 0; i < nodes.length && i < 200; i++) {
        var d = describe(nodes[i]);
        if (d && d.visible) out.push(d);
      }
      return JSON.stringify(out);
    } catch (e) { return '[]'; }
  };

  /** 点某个选择器（等价的 element.click()）。 */
  P.click = function (selector) {
    try {
      var el = document.querySelector(selector);
      if (!el) return 'false';
      el.click();
      return 'true';
    } catch (e) { return 'false'; }
  };

  /** 任意 JS 求值（调试用）。 */
  P.evaluate = function (js) {
    try { return String((0, eval)(js)); } catch (e) { return 'ERROR: ' + e; }
  };

  /** 内容高度（宿主用来给内嵌块定高）。 */
  P.contentHeight = function () {
    try {
      var d = document.documentElement, b = document.body;
      return Math.max(d ? d.scrollHeight : 0, b ? b.scrollHeight : 0);
    } catch (e) { return 0; }
  };

  /** 视口尺寸（CSS px）。 */
  P.viewport = function () {
    try { return JSON.stringify({ w: window.innerWidth, h: window.innerHeight }); }
    catch (e) { return '{}'; }
  };

  /** 页面标题。 */
  P.title = function () {
    try { return document.title || ''; } catch (e) { return ''; }
  };

  /** 当前 URL（含 hash 变化）。 */
  P.url = function () {
    try { return String(location.href); } catch (e) { return ''; }
  };

  /**
   * 观察内容高度并回报（kind = "size"）。
   *
   * 内嵌 HTML 块要用它给 WebView 定高 —— 题目页有 3 道题就 3 道题高，
   * 有 30 道就 30 道高，不能一刀切。
   */
  P.observeSize = function () {
    try {
      if (P._ro) return;
      var last = -1;
      var send = function () {
        var h = P.contentHeight();
        if (h > 0 && h !== last) { last = h; post('size', String(h)); }
      };
      if (window.ResizeObserver) {
        P._ro = new ResizeObserver(send);
        P._ro.observe(document.documentElement);
        if (document.body) P._ro.observe(document.body);
      }
      window.addEventListener('resize', send);
      window.addEventListener('load', send);
      setTimeout(send, 60);
      setTimeout(send, 300);
    } catch (e) {}
  };

  // ---- 元素选择器 ----

  var pick = null;

  function stopPick() {
    if (!pick) return;
    try {
      // 用 remove() 而不是 parentNode.removeChild() ——
      // 后者要求元素还挂在树上，且多一层 parentNode 依赖。
      // remove() 是标准 DOM 方法，元素已被移走时也不报错。
      if (pick.overlay) pick.overlay.remove();
      if (pick.hi) pick.hi.remove();
      if (pick.tip) pick.tip.remove();
      if (pick.bub) pick.bub.remove();
    } catch (e) {}
    pick = null;
  }

  /** 点击特效：原地画一圈扩散的环。 */
  function clickFx(x, y) {
    try {
      var ring = mk('div',
        'position:fixed;left:' + (x - 6) + 'px;top:' + (y - 6) + 'px;width:12px;height:12px;' +
        'margin:-6px 0 0 -6px;border:2px solid #8a5a3b;border-radius:50%;z-index:2147483645;' +
        'pointer-events:none;transition:transform .32s ease-out,opacity .32s ease-out;');
      document.body.appendChild(ring);
      requestAnimationFrame(function () {
        ring.style.transform = 'scale(6)';
        ring.style.opacity = '0';
      });
      setTimeout(function () {
        if (ring.parentNode) ring.parentNode.removeChild(ring);
      }, 360);
    } catch (e) {}
  }

  /** 把气泡摆到元素旁边，并保证不出视口。 */
  function placeBubble(r) {
    var b = pick.bub;
    b.style.display = 'block';
    var w = b.offsetWidth, h = b.offsetHeight;
    // 矩形用 x/y（rectOf 的字段名）—— 之前这里读 r.left/r.top，
    // 而 describe 给的是 x/y，两者对不上，气泡就摆到了 undefined 的位置。
    var left = r.x, top = r.y + r.height + 8;
    if (top + h > window.innerHeight - 8) top = Math.max(8, r.y - h - 8);
    if (left + w > window.innerWidth - 8) left = Math.max(8, window.innerWidth - w - 8);
    b.style.left = left + 'px';
    b.style.top = top + 'px';
  }

  function showBubble(el, info) {
    var b = pick.bub;
    b.innerHTML = '';
    var head = mk('div',
      'font:600 11px/1.4 -apple-system,sans-serif;color:#8a5a3b;margin-bottom:5px;');
    head.textContent = info.tag.toUpperCase() + (info.id ? ('#' + info.id) : '');
    var body = mk('div',
      'font:12px/1.5 -apple-system,sans-serif;color:#6f6a62;max-width:190px;' +
      'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-bottom:7px;');
    body.textContent = info.text || info.selector || '';

    // 备注输入框：用户可以一边看着元素一边说要怎么改，
    // 不用退出浏览器回去打字（文字会随元素一起回传）。
    var note = mk('input', 'all:unset;box-sizing:border-box;width:196px;' +
      'background:#f4f0e8;border:1px solid #e3ddd2;border-radius:7px;' +
      'padding:6px 8px;margin-bottom:7px;font:12px/1.4 -apple-system,sans-serif;' +
      'color:#2b2926;outline:none;');
    note.type = 'text';
    note.placeholder = '补充说明（可留空）';
    note.setAttribute('autocomplete', 'off');

    var row = mk('div', 'display:flex;gap:6px;');
    var add = mk('button',
      'all:unset;cursor:pointer;background:#8a5a3b;color:#fff;border-radius:7px;' +
      'padding:5px 11px;font:600 12px/1.4 -apple-system,sans-serif;');
    add.textContent = '添加到对话';
    add.onclick = function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      // 备注随元素一起回传：壳侧把两者一起放进输入框
      info.note = note.value || '';
      post('element', JSON.stringify(info));
      add.textContent = '已添加 ✓';
      add.style.background = '#3f6f4f';
      setTimeout(function () { stopPick(); }, 420);
    };
    var cancel = mk('button',
      'all:unset;cursor:pointer;background:#e3ddd2;color:#2b2926;border-radius:7px;' +
      'padding:5px 10px;font:600 12px/1.4 -apple-system,sans-serif;');
    cancel.textContent = '取消';
    cancel.onclick = function (ev) { ev.preventDefault(); ev.stopPropagation(); stopPick(); };
    row.appendChild(add); row.appendChild(cancel);
    b.appendChild(head); b.appendChild(body); b.appendChild(note); b.appendChild(row);
    placeBubble(info.rect);
    // 点输入框不能被当成「挑元素」——覆盖层盖在上面，事件会被它吃掉
    try { note.focus({ preventScroll: true }); } catch (e) { try { note.focus(); } catch (e2) {} }
  }

  /** 开/关元素选择模式。 */
  P.startPick = function () {
    if (pick) return;
    var ov = mk('div',
      'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483640;' +
      'background:transparent;cursor:crosshair;');
    var hi = mk('div',
      'position:fixed;z-index:2147483641;pointer-events:none;display:none;' +
      'border:2px solid #8a5a3b;background:rgba(138,90,59,.16);border-radius:6px;' +
      'box-sizing:border-box;');
    var tip = mk('div',
      'position:fixed;z-index:2147483641;pointer-events:none;display:none;' +
      'background:#2b2926;color:#f7f5f0;border-radius:5px;padding:2px 7px;' +
      'font:11px/1.5 -apple-system,sans-serif;white-space:nowrap;');
    var bub = mk('div',
      'position:fixed;z-index:2147483647;display:none;background:#fffdf8;' +
      'border:1px solid #e3ddd2;border-radius:11px;padding:9px 11px;' +
      'box-shadow:0 8px 26px rgba(0,0,0,.20);box-sizing:border-box;');

    document.body.appendChild(ov);
    document.body.appendChild(hi);
    document.body.appendChild(tip);
    document.body.appendChild(bub);

    var current = null;

    /**
     * 按坐标找元素 —— 触屏与鼠标共用一条路。
     *
     * 为什么不靠 hover 状态：触屏设备上根本没有 mousemove，
     * 覆盖层下的元素永远不会被「悬停」，点选就取不到元素。
     * 所以每次都按坐标现场问一次 DOM。
     *
     * **要点**：覆盖层自己就盖在那个坐标上，`elementFromPoint` 返回的是它。
     * 必须把「命中自己」的情况再往下钻一层 —— 用 `elementsFromPoint`
     * 拿整条命中链，取第一个不是覆盖层/高亮/提示/气泡的元素。
     */
    function pickAt(x, y) {
      var chain = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
      for (var i = 0; i < chain.length; i++) {
        var el = chain[i];
        if (el === ov || el === hi || el === tip || el === bub) continue;
        return el;
      }
      // 兜底：老 WebView 没有 elementsFromPoint
      var el = document.elementFromPoint(x, y);
      if (el && el !== ov && el !== hi && el !== tip && el !== bub) return el;
      return null;
    }

    /** 显示高亮框与尺寸提示。 */
    function highlight(el) {
      if (!el) {
        current = null;
        hi.style.display = 'none';
        tip.style.display = 'none';
        return;
      }
      current = el;
      var r = el.getBoundingClientRect();
      hi.style.display = 'block';
      hi.style.left = r.left + 'px';
      hi.style.top = r.top + 'px';
      hi.style.width = r.width + 'px';
      hi.style.height = r.height + 'px';
      tip.style.display = 'block';
      tip.textContent = el.tagName.toLowerCase() +
        (el.id ? ('#' + el.id) : '') +
        '  ' + Math.round(r.width) + '×' + Math.round(r.height);
      var tw = tip.offsetWidth, th = tip.offsetHeight;
      var tl = r.left, tt = r.top - th - 4;
      if (tt < 4) tt = r.bottom + 4;
      if (tl + tw > window.innerWidth - 4) tl = window.innerWidth - tw - 4;
      tip.style.left = tl + 'px';
      tip.style.top = tt + 'px';
    }

    /** 鼠标移动：跟着指针预览元素。 */
    ov.addEventListener('mousemove', function (ev) {
      highlight(pickAt(ev.clientX, ev.clientY));
    });

    /**
     * 触屏移动：手指划过时同样预览。
     *
     * touch 事件的坐标在 `touches[0]`，不在事件本身。
     */
    ov.addEventListener('touchmove', function (ev) {
      if (!ev.touches || !ev.touches.length) return;
      var t = ev.touches[0];
      highlight(pickAt(t.clientX, t.clientY));
    }, { passive: true });

    ov.addEventListener('mouseleave', function () {
      hi.style.display = 'none';
      tip.style.display = 'none';
    }, true);

    /** 点选：按坐标现场取元素，不依赖 hover 状态（触屏没有 hover）。 */
    function pickAtPoint(x, y) {
      var el = pickAt(x, y) || current;
      if (!el) return;
      clickFx(x, y);
      var info = describe(el);
      if (info) showBubble(el, info);
    }

    ov.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      pickAtPoint(ev.clientX, ev.clientY);
    }, true);

    /**
     * 触屏点选。
     *
     * touchend 的坐标在 `changedTouches[0]`。
     * preventDefault 阻止后续的 click 重复触发一次点选。
     */
    ov.addEventListener('touchend', function (ev) {
      if (!ev.changedTouches || !ev.changedTouches.length) return;
      var t = ev.changedTouches[0];
      ev.preventDefault();
      ev.stopPropagation();
      pickAtPoint(t.clientX, t.clientY);
    }, true);

    pick = { overlay: ov, hi: hi, tip: tip, bub: bub };
  };

  P.stopPick = stopPick;
  P.picking = function () { return !!pick; };
})();
"""

    /**
     * 页面 → 壳侧的回调接口。
     *
     * 只暴露 [post]。页面可以伪造事件，但最多只能伪造一条元素描述，
     * 换不来任何越权能力。
     */
    class AndroidBridge(private val onEvent: (BridgeEvent) -> Unit) {

        @JavascriptInterface
        fun post(kind: String, payload: String) {
            onEvent(BridgeEvent(kind, payload))
        }
    }

    /**
     * 把桥挂到 WebView 上。
     *
     * [allowBridge] 为 false 时不挂 —— `file://` 页面一律走这条路，
     * 免得本地文件里的脚本拿到壳侧回调。
     */
    fun attach(webView: WebView, allowBridge: Boolean, onEvent: (BridgeEvent) -> Unit) {
        if (allowBridge) {
            webView.addJavascriptInterface(AndroidBridge(onEvent), "__cfAndroid")
        }
        webView.evaluateJavascript(SCRIPT, null)
    }

    /** 从 JSON 文本解析出元素信息；解析失败返回 null。 */
    fun parseElement(payload: String): PickedElement? = runCatching {
        val o = JSONObject(payload)
        val r = o.optJSONObject("rect") ?: JSONObject()
        val styles = mutableMapOf<String, String>()
        o.optJSONObject("styles")?.let { s ->
            s.keys().forEach { k -> styles[k] = s.optString(k) }
        }
        PickedElement(
            index = 0,
            tag = o.optString("tag"),
            id = o.optString("id"),
            cls = o.optString("cls"),
            text = o.optString("text"),
            selector = o.optString("selector"),
            rect = ElementRect(
                x = r.optDouble("x", 0.0).toFloat(),
                y = r.optDouble("y", 0.0).toFloat(),
                width = r.optDouble("width", 0.0).toFloat(),
                height = r.optDouble("height", 0.0).toFloat(),
            ),
            styles = styles,
            pageUrl = o.optString("pageUrl"),
            html = o.optString("html"),
            userNote = o.optString("note"),
        )
    }.getOrNull()
}
