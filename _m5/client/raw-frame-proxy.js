// Transparent TCP proxy used to prove which Java packet ids actually leave a
// minecraft-protocol / GUI client and reach ViaProxy. Logs VarInt-framed packet
// ids in both directions without touching payloads.
//
// This version is state + compression aware:
//   * Before SET_COMPRESSION the frame payload starts with the packet id.
//   * After SET_COMPRESSION the payload starts with a VarInt "data length":
//       0 => uncompressed, the packet id follows directly
//       N => zlib-compressed N-byte payload, inflate it before reading the id
//   * SET_COMPRESSION itself (clientbound LOGIN id 0x03) is never compressed.
//
// Usage: node raw-frame-proxy.js <listenPort> <targetHost> <targetPort> <logPath>
const net = require('net')
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')
const [listenPort, targetHost, targetPort, logPath] = process.argv.slice(2)
const out = fs.createWriteStream(logPath || 'raw-frames.log', { flags: 'a' })
const t0 = Date.now()
const log = (o) => out.write(JSON.stringify({ ms: Date.now() - t0, ...o }) + '\n')

function readVarInt(buf, off) {
  let value = 0, shift = 0, pos = off
  while (pos < buf.length) {
    const b = buf[pos++]
    value |= (b & 0x7f) << shift
    if ((b & 0x80) === 0) return [value, pos]
    shift += 7
    if (shift > 35) return null
  }
  return null
}

// Optional id -> name mapping from minecraft-data (1.21.1 wire ids).
let names = null
try {
  const p = path.join(__dirname, 'node_modules', 'minecraft-data', 'minecraft-data', 'data', 'pc', '1.21.1', 'protocol.json')
  const proto = JSON.parse(fs.readFileSync(p, 'utf8'))
  const mappings = proto.play.toServer.types.packet[1][0].type[1].mappings
  names = {}
  for (const [k, v] of Object.entries(mappings)) names[parseInt(k, 16)] = v
} catch (e) {
  log({ ev: 'name_map_unavailable', msg: String(e && e.message) })
}

function frameParser(label, shared) {
  let pending = Buffer.alloc(0)
  return (chunk) => {
    pending = pending.length ? Buffer.concat([pending, chunk]) : chunk
    let off = 0
    while (off < pending.length) {
      const lenRes = readVarInt(pending, off)
      if (!lenRes) break
      const [len, afterLen] = lenRes
      if (len < 0 || afterLen + len > pending.length) break
      const payload = pending.subarray(afterLen, afterLen + len)
      let id = -1, dataLen = null, compressed = false, error = null
      try {
        if (!shared.compressed) {
          const r = readVarInt(payload, 0)
          if (r) id = r[0]
          // SET_COMPRESSION is clientbound LOGIN id 0x03 and is itself uncompressed.
          if (label === 'S2C' && id === 0x03 && !shared.compressionSeen) {
            shared.compressionSeen = true
            shared.compressed = true
            log({ ev: 'compression_enabled', threshold: readVarInt(payload, r ? r[1] : 1)?.[0] })
          }
        } else {
          const dl = readVarInt(payload, 0)
          if (!dl) { error = 'bad_data_length' } else {
            dataLen = dl[0]
            let body
            if (dataLen === 0) {
              body = payload.subarray(dl[1])
            } else {
              compressed = true
              body = zlib.inflateSync(payload.subarray(dl[1]))
            }
            const r = readVarInt(body, 0)
            if (r) id = r[0]
          }
        }
      } catch (e) {
        error = String(e && e.message)
      }
      log({
        dir: label,
        id,
        name: names && names[id] ? names[id] : undefined,
        len,
        dataLen,
        compressed,
        state: shared.compressed ? 'compressed' : 'plain',
        hex: payload.subarray(0, Math.min(payload.length, 16)).toString('hex'),
        ...(error ? { error } : {})
      })
      off = afterLen + len
    }
    pending = pending.subarray(off)
  }
}

net.createServer((clientSocket) => {
  const target = net.connect(Number(targetPort), targetHost)
  const shared = { compressed: false, compressionSeen: false }
  const fromClient = frameParser('C2S', shared)
  const fromServer = frameParser('S2C', shared)
  clientSocket.on('data', (d) => { fromClient(d); target.write(d) })
  target.on('data', (d) => { fromServer(d); clientSocket.write(d) })
  clientSocket.on('error', (e) => log({ ev: 'client_error', msg: String(e && e.message) }))
  target.on('error', (e) => log({ ev: 'target_error', msg: String(e && e.message) }))
  clientSocket.on('close', () => { target.end(); log({ ev: 'close' }) })
  target.on('close', () => clientSocket.end())
}).listen(Number(listenPort), '127.0.0.1', () => log({ ev: 'listening', listenPort, targetHost, targetPort }))
