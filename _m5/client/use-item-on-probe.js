// Minimal protocol-level probe: connect a real Java client socket and send one
// serverbound `block_place` (USE_ITEM_ON) packet. Used to reproduce the
// 1.21-1.21.1 -> Bedrock 1.26.51 USE_ITEM_ON schema mismatch without needing
// GUI automation. No raw Minecraft bytes are injected: node-minecraft-protocol
// serialises the packet from the 1.21.1 schema.
//
// Env: MC_HOST, MC_PORT, MC_VERSION, MC_USER, WP_HOLD_MS
const mc = require('minecraft-protocol')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25569)
const version = process.env.MC_VERSION || '1.21.1'
const username = process.env.MC_USER || 'UseItemProbe'
const holdMs = Number(process.env.WP_HOLD_MS || 6000)
const errors = []

const client = mc.createClient({ host, port, username, version, auth: 'offline' })
const log = (ev, detail = {}) => console.log(JSON.stringify({ ev, ...detail }))

client.on('error', e => { errors.push('error:' + String(e && e.message)); log('error', { message: String(e && e.message) }) })
client.on('kicked', r => { errors.push('kicked:' + JSON.stringify(r)); log('kicked', { reason: JSON.stringify(r) }) })
client.on('end', r => log('end', { reason: r || 'quit' }))

client.on('login', () => {
  log('login', { version })
  // Wait briefly for the proxy to finish PLAY initialisation.
  setTimeout(() => {
    try {
      client.write('block_place', {
        hand: 0,
        location: { x: 0, y: -60, z: 0 },
        direction: 1,
        cursorX: 0.5,
        cursorY: 0.5,
        cursorZ: 0.5,
        insideBlock: false,
        sequence: 0
      })
      log('sent_block_place', {})
    } catch (e) {
      errors.push('write:' + String(e && e.message))
      log('write_error', { message: String(e && e.message) })
    }
  }, 1500)
})

setTimeout(() => {
  log('final', { stillConnected: !client.ended, errors })
  try { client.end() } catch {}
  setTimeout(() => process.exit(errors.length ? 3 : 0), 300)
}, holdMs)
