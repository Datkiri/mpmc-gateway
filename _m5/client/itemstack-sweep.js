// .13-exp sweep driver: one Java CLI connection, many ITEM_STACK_REQUEST variants.
// Before every set_creative_slot it rewrites the gateway control file, so the
// gateway builds a different Bedrock ITEM_STACK_REQUEST for the same Java packet.
//
// Env:
//   MC_PORT        gateway TCP port (default 25571)
//   MC_VERSION     Java client version (default 1.21.1)
//   MC_USER        username
//   MC_CONTROL     absolute path of the gateway control file
//   MC_SWEEP_FILE  JSON file containing an array of control strings
//   MC_GAP_MS      delay between requests (default 900)
//   MC_TAIL_MS     delay after the last request (default 2500)
const fs = require('fs')
const mc = require('minecraft-protocol')

const port = Number(process.env.MC_PORT || 25571)
const version = process.env.MC_VERSION || '1.21.1'
const user = process.env.MC_USER || ('Sweep' + Math.floor(Math.random() * 100000))
const control = process.env.MC_CONTROL
const sweepFile = process.env.MC_SWEEP_FILE
const gap = Number(process.env.MC_GAP_MS || 900)
const tail = Number(process.env.MC_TAIL_MS || 2500)
const sameSlot = process.env.MC_SAME_SLOT === '1'

if (!control || !sweepFile) {
  console.error('MC_CONTROL and MC_SWEEP_FILE are required')
  process.exit(2)
}
const controls = JSON.parse(fs.readFileSync(sweepFile, 'utf8'))
if (!Array.isArray(controls) || controls.length === 0) {
  console.error('MC_SWEEP_FILE must contain a non-empty JSON array')
  process.exit(2)
}

const Item = require('prismarine-item')(version)
const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))

const client = mc.createClient({ host: '127.0.0.1', port, username: user, version, auth: 'offline' })
client.on('error', e => log('error', { message: String(e && e.message) }))
client.on('kicked', r => log('kicked', { reason: JSON.stringify(r) }))
client.on('end', r => log('end', { reason: r || 'quit' }))

client.on('login', () => {
  log('login', { version, user, count: controls.length, port })
  controls.forEach((cfg, i) => {
    setTimeout(() => {
      try {
        fs.writeFileSync(control, cfg + '\n', 'utf8')
        // Java slots 9..44 map to 36 distinct Bedrock inventory slots, so no
        // two cases can contaminate each other's server-side slot state.
        const slot = sameSlot ? 36 : 9 + (i % 36)
        const jm = /(?:^|,)j=(\d+)/.exec(cfg)
        const cm = /(?:^|,)c=(\d+)/.exec(cfg)
        const id = jm ? Number(jm[1]) : 1
        const count = cm ? Number(cm[1]) : 16
        const item = new Item(id, count)
        // A real 1.21.1 GUI creative client sends these default data components.
        if (/(?:^|,)comp=1/.test(cfg)) {
          item.components = [
            { type: 'max_stack_size', data: 64 },
            { type: 'damage', data: 0 },
            { type: 'rarity', data: 0 }
          ]
        }
        client.write('set_creative_slot', { slot, item: Item.toNotch(item) })
        log('sent', { i, cfg, slot, id, count, comp: /(?:^|,)comp=1/.test(cfg) })
      } catch (e) {
        log('write_error', { i, message: String(e && e.message) })
      }
    }, 1200 + i * gap)
  })
  setTimeout(() => client.end(), 1200 + controls.length * gap + tail)
})
