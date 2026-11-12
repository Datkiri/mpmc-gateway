// Long-lived single-connection reproduction for the GUI-only status 6 / 60 storm.
//
// Real Java 1.21.1 GUI session (vp-live-25569.clean.log) accepted 13 creative
// requests until 16:52:01, then rejected EVERY request from 16:52:55 until
// disconnect 17:08:46. All earlier CLI probes were short (< 60 s) and never
// reproduced it. This driver keeps ONE connection alive for ~150 s with a 20 Hz
// movement pump (the GUI emits PlayerAuthInput continuously; idle CLI probes do
// not) and a creative click every 3 s, including delete (air) clicks.
//
// Goal: obtain a before/after pair on the SAME probe (status 0 -> status 6/60).
// No raw Minecraft bytes injected. Env: MC_PORT, MC_USER, MC_DURATION_MS,
// MC_CLICK_GAP_MS.
const mc = require('minecraft-protocol')
const md = require('minecraft-data')('1.21.1')
const Item = require('prismarine-item')('1.21.1')

const version = process.env.MC_VERSION || '1.21.1'
const port = Number(process.env.MC_PORT || 25570)
const user = process.env.MC_USER || 'A18LONGRUN'
const duration = Number(process.env.MC_DURATION_MS || 150000)
const gap = Number(process.env.MC_CLICK_GAP_MS || 3000)
const stoneId = md.itemsByName.stone.id
const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))

const client = mc.createClient({ host: '127.0.0.1', port, username: user, version, auth: 'offline' })
const errors = []
client.on('error', e => { errors.push(String(e && e.message)); log('error', { message: String(e && e.message) }) })
client.on('kicked', r => { errors.push('kicked:' + JSON.stringify(r)); log('kicked', { reason: JSON.stringify(r) }) })
client.on('end', r => log('end', { reason: r || 'quit', errors }))

let pump = null
let clicks = 0
const items = [stoneId, md.itemsByName.crafting_table.id, md.itemsByName.oak_sapling.id, 0]
const notch = (id, count) => Item.toNotch(new Item(id, count, 0))

client.on('login', () => {
  log('login', { version, user, port, duration, gap })

  // 20 Hz movement pump so ViaBedrock keeps emitting Bedrock PlayerAuthInput.
  let tick = 0
  pump = setInterval(() => {
    tick++
    try {
      client.write('position', { x: 0.5, y: -60, z: 0.5, onGround: true })
      client.write('look', { yaw: (tick % 360) * 0.01, pitch: 0, onGround: true })
    } catch (e) { log('pump_error', { message: String(e && e.message) }) }
  }, 50)

  const timer = setInterval(() => {
    const n = clicks++
    const slot = 36 + (n % 9)
    const id = items[n % items.length]
    try {
      client.write('set_creative_slot', { slot, item: notch(id, id === 0 ? 1 : ((n % 4) + 1)) })
      log('sent_creative', { n, slot, id, count: id === 0 ? 1 : ((n % 4) + 1) })
    } catch (e) { log('write_error', { n, message: String(e && e.message) }) }
  }, gap)

  setTimeout(() => {
    clearInterval(timer)
    if (pump) clearInterval(pump)
    log('done', { clicks, errors })
    try { client.end() } catch (e) {}
  }, duration)
})
