// Minimal Java 1.21.1 client for handshake connect-storm experiments.
// Logs in, holds the connection briefly so BDS can deliver START_GAME, then ends.
const mc = require('minecraft-protocol')

const port = Number(process.env.MC_PORT || 25597)
const user = process.env.MC_USER || 'STORM'
const hold = Number(process.env.MC_HOLD_MS || 1500)
const client = mc.createClient({
  host: '127.0.0.1', port, username: user, version: '1.21.1', auth: 'offline'
})
const log = (ev, d = {}) => console.log(JSON.stringify({ ev, ...d }))
client.on('error', e => log('error', { m: String(e && e.message) }))
client.on('kicked', r => log('kicked', { r: JSON.stringify(r) }))
client.on('end', r => { log('end', { r: r || 'quit' }); process.exit(0) })
client.on('login', () => {
  log('login')
  setTimeout(() => client.end(), hold)
})
const guard = setTimeout(() => { log('timeout'); client.end() }, 15000)
guard.unref()
