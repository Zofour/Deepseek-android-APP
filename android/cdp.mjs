// Minimal CDP client for the app's WebView (debug build enables webContentsDebugging).
// usage:
//   node cdp.mjs list
//   node cdp.mjs eval "<javascript expression>"     -> prints JSON value
//   node cdp.mjs shot <out.png> [--full]
//   node cdp.mjs reload
// Requires: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
import { writeFileSync } from 'node:fs'

const ENDPOINT = process.env.CDP || 'http://127.0.0.1:9222'
const cmd = process.argv[2] || 'list'

/** stdout 在某些终端里会被丢掉，同时落一份到 cdp_last.txt */
function emit(text) {
  process.stdout.write(String(text) + String.fromCharCode(10))
  try {
    writeFileSync(new URL('./cdp_last.txt', import.meta.url), String(text))
  } catch (e) {}
}

async function targets() {
  const res = await fetch(`${ENDPOINT}/json`)
  return res.json()
}

function pickPage(list) {
  return (
    list.find((t) => t.type === 'page' && (t.url || '').includes('appassets')) ||
    list.find((t) => t.type === 'page') ||
    list[0]
  )
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl)
    let id = 0
    const pending = new Map()
    ws.addEventListener('message', (ev) => {
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch {
        return
      }
      if (msg.id && pending.has(msg.id)) {
        const { resolve: r, reject: j } = pending.get(msg.id)
        pending.delete(msg.id)
        if (msg.error) j(new Error(JSON.stringify(msg.error)))
        else r(msg.result)
      }
    })
    ws.addEventListener('error', (e) => reject(new Error('ws error: ' + e.message)))
    ws.addEventListener('open', () =>
      resolve({
        send(method, params = {}, timeoutMs = 30000) {
          const mid = ++id
          return new Promise((r, j) => {
            pending.set(mid, { resolve: r, reject: j })
            ws.send(JSON.stringify({ id: mid, method, params }))
            setTimeout(() => {
              if (pending.has(mid)) {
                pending.delete(mid)
                j(new Error('timeout: ' + method))
              }
            }, timeoutMs)
          })
        },
        close() {
          ws.close()
        },
      })
    )
  })
}

const list = await targets()
if (cmd === 'list') {
  emit(
    JSON.stringify(
      list.map((t) => ({ id: t.id, type: t.type, title: t.title, url: t.url })),
      null,
      2
    )
  )
  process.exit(0)
}

const page = pickPage(list)
if (!page) {
  emit('no debuggable page found')
  process.exit(2)
}
const cdp = await connect(page.webSocketDebuggerUrl)
await cdp.send('Runtime.enable')
await cdp.send('Page.enable')

if (cmd === 'eval') {
  const expr = process.argv[3]
  const res = await cdp.send(
    'Runtime.evaluate',
    { expression: expr, awaitPromise: true, returnByValue: true, userGesture: true },
    60000
  )
  if (res.exceptionDetails) {
    emit('EXCEPTION: ' + JSON.stringify(res.exceptionDetails.exception?.description || res.exceptionDetails))
    process.exitCode = 1
  } else {
    const v = res.result?.value
    emit(typeof v === 'string' ? v : JSON.stringify(v, null, 2))
  }
} else if (cmd === 'tap') {
  const x = Number(process.argv[3])
  const y = Number(process.argv[4])
  const base = { x, y, button: 'left', clickCount: 1, pointerType: 'touch' }
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...base })
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...base })
  emit(`tapped ${x},${y}`)
} else if (cmd === 'type') {
  const text = process.argv[3] ?? ''
  await cdp.send('Input.insertText', { text })
  emit('typed ' + text.length + ' chars')
} else if (cmd === 'runfile') {
  const { readFileSync } = await import('node:fs')
  const expr = readFileSync(process.argv[3], 'utf8')
  const res = await cdp.send(
    'Runtime.evaluate',
    { expression: expr, awaitPromise: true, returnByValue: true, userGesture: true },
    120000
  )
  if (res.exceptionDetails) {
    emit('EXCEPTION: ' + (res.exceptionDetails.exception?.description || JSON.stringify(res.exceptionDetails)))
    process.exitCode = 1
  } else {
    const v = res.result?.value
    emit(typeof v === 'string' ? v : JSON.stringify(v, null, 2))
  }
} else if (cmd === 'shot') {
  const out = process.argv[3] || 'cdp-shot.png'
  const full = process.argv.includes('--full')
  const res = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: full })
  writeFileSync(out, Buffer.from(res.data, 'base64'))
  emit('saved ' + out)
} else if (cmd === 'reload') {
  await cdp.send('Page.reload', {})
  emit('reloaded')
}
cdp.close()
process.exit(0)
