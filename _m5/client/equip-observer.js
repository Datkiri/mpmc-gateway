// Passive observer: connect to a gateway sharing the same BDS and log every
// Java clientbound packet that could carry another player's equipment or
// inventory content. Used to prove whether BDS actually applied the
// MOB_EQUIPMENT sent by the equipper, instead of silently ignoring it.
const mc = require('minecraft-protocol')

const port = Number(process.env.MC_PORT || 25583)
const user = process.env.MC_USER || 'EquipObserver'
const version = process.env.MC_VERSION || '1.21.1'
const hold = Number(process.env.MC_HOLD_MS || 14000)

const client = mc.createClient({
  host: '127.0.0.1', port, username: user, version, auth: 'offline'
})

const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))
client.on('error', e => log('error', { message: String(e && e.message) }))
client.on('kicked', r => log('kicked', { reason: JSON.stringify(r) }))
client.on('end', r => log('end', { reason: String(r || 'quit') }))
client.on('login', () => log('login', { user, port, version }))

client.on('packet', (data, meta) => {
  const n = (meta && meta.name) || ''
  if (/equipment|window_items|set_slot|held_item_slot|container/i.test(n)) {
    log('packet', { name: n, data: JSON.stringify(data).slice(0, 500) })
  }
})

setTimeout(() => { client.end(); log('observer_done'); process.exit(0) }, hold)
