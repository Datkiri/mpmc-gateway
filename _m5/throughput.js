const net = require('net')

const host = process.env.TH_HOST || '127.0.0.1'
const port = Number(process.env.TH_PORT || 25566)
const totalBytes = Number(process.env.TH_BYTES || 50 * 1024 * 1024)
const streams = Number(process.env.TH_STREAMS || 1)
const chunk = Buffer.alloc(256 * 1024, 0x5a)

function runStream(index) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port })
    socket.setNoDelay(true)
    let sent = 0
    let received = 0
    const started = process.hrtime.bigint()
    let settled = false

    const timer = setTimeout(() => done(new Error(`timeout at ${received}/${totalBytes}`)), 600000)
    timer.unref()

    const done = (err) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      if (progress) clearInterval(progress)
      const ms = Number(process.hrtime.bigint() - started) / 1e6
      if (process.env.TH_LOG_PROGRESS) console.error(`stream ${index} done bytes=${received} err=${err?.message || '-'}`)
      socket.destroy()
      err ? reject(err) : resolve({ index, bytes: received, ms })
    }

    let progress = null
    if (process.env.TH_LOG_PROGRESS) {
      progress = setInterval(() => {
        console.error(`stream ${index} sent=${sent} recv=${received} connected=${socket.connecting === false}`)
      }, 2000)
      progress.unref()
    }

    socket.on('connect', () => {
      const pump = () => {
        while (sent < totalBytes) {
          const n = Math.min(chunk.length, totalBytes - sent)
          sent += n
          if (!socket.write(n === chunk.length ? chunk : chunk.subarray(0, n))) {
            socket.once('drain', pump)
            return
          }
        }
      }
      pump()
    })
    socket.on('data', d => {
      received += d.length
      if (received >= totalBytes) done()
    })
    socket.on('error', done)
    socket.on('close', () => {
      if (received < totalBytes) done(new Error(`closed at ${received}/${totalBytes}`))
    })
    setTimeout(() => done(new Error(`timeout at ${received}/${totalBytes}`)), 600000)
  })
}

;(async () => {
  const started = Date.now()
  const results = await Promise.all(Array.from({ length: streams }, (_, i) => runStream(i)))
  const wallMs = Date.now() - started
  const perStream = results.map(r => +(r.bytes / 1048576 / (r.ms / 1000)).toFixed(1))
  const aggregate = +(results.reduce((a, r) => a + r.bytes, 0) / 1048576 / (wallMs / 1000)).toFixed(1)
  console.log(JSON.stringify({
    streams,
    bytesPerStream: totalBytes,
    wallMs,
    aggregateMiBs: aggregate,
    perStreamMiBs: perStream,
    minMiBs: Math.min(...perStream),
  }))
  process.exit(0)
})().catch(e => { console.error(e.message); process.exit(1) })
