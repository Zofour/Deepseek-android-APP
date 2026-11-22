/* 注入脚本：把网页里的 blob 下载/新窗口打开，转交给原生壳处理。
   只在 WebView 内生效，作用等价于给浏览器补上「保存文件」能力。 */
(function () {
  if (window.__deepseekAppPatched) return
  window.__deepseekAppPatched = true

  var bridge = window.DeepSeekAndroid

  function mimeOf(name) {
    var ext = (name.split('.').pop() || '').toLowerCase()
    if (ext === 'zip') return 'application/zip'
    if (ext === 'json') return 'application/json'
    if (ext === 'md' || ext === 'txt') return 'text/plain'
    return 'application/octet-stream'
  }

  async function saveBlob(url, name) {
    try {
      var res = await fetch(url)
      var buf = new Uint8Array(await res.arrayBuffer())
      if (!bridge) return
      if (!bridge.blobBegin(name, mimeOf(name))) {
        bridge.toast('无法创建文件')
        return
      }
      var CHUNK = 256 * 1024
      for (var i = 0; i < buf.length; i += CHUNK) {
        var sub = buf.subarray(i, Math.min(i + CHUNK, buf.length))
        var bin = ''
        for (var j = 0; j < sub.length; j++) bin += String.fromCharCode(sub[j])
        bridge.blobChunk(btoa(bin))
      }
      bridge.blobEnd()
    } catch (e) {
      if (bridge) bridge.toast('保存失败：' + e)
    }
  }

  // 拦截「带 download 属性」的锚点点击（导出会话 ZIP 走这条路）
  var origClick = HTMLAnchorElement.prototype.click
  HTMLAnchorElement.prototype.click = function () {
    try {
      var href = this.href || ''
      var download = this.getAttribute('download')
      if (download && href.indexOf('blob:') === 0) {
        saveBlob(href, download)
        return
      }
      if (href.indexOf('blob:') === 0 && !download) {
        return origClick.apply(this, arguments)
      }
    } catch (e) {
      /* 出错就走原生逻辑 */
    }
    return origClick.apply(this, arguments)
  }

  // target=_blank / window.open：改为当前 WebView 内导航，交给原生判定（外链转浏览器）
  window.open = function (u) {
    if (u) window.location.href = String(u)
    return null
  }

  // 把页面实际主题色同步给状态栏/导航栏，避免深色系统 + 浅色页面时上下黑边
  function pushThemeColor() {
    try {
      if (!bridge || !bridge.setThemeColor) return
      var meta = document.querySelector('meta[name="theme-color"]')
      var color = meta ? meta.getAttribute('content') : ''
      if (!color) color = getComputedStyle(document.body).backgroundColor
      var isDark = document.body.classList.contains('dark')
      bridge.setThemeColor(color, isDark)
    } catch (e) {
      /* 忽略 */
    }
  }

  function watchTheme() {
    var meta = document.querySelector('meta[name="theme-color"]')
    if (meta) {
      new MutationObserver(pushThemeColor).observe(meta, {
        attributes: true,
        attributeFilter: ['content'],
      })
    }
    new MutationObserver(pushThemeColor).observe(document.body, {
      attributes: true,
      attributeFilter: ['class', 'data-ds-dark-theme', 'style'],
    })
    pushThemeColor()
    setTimeout(pushThemeColor, 300)
  }

  if (document.readyState === 'complete') watchTheme()
  else window.addEventListener('load', watchTheme, { once: true })
})()
