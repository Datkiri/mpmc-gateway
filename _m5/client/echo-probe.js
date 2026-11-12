// Verify the Java 1.21.1 client really receives the creative slot echo.
// gui-repro.js counts only set_slot/container_set_content/set_creative_slot,
// but minecraft-protocol 1.21.1 may name CONTAINER_SET_CONTENT differently.
// This probe logs every clientbound packet name and the inventory payloads.
const mc = require('minecraft-protocol')
const Item = require('prismarine-item')('1.21.1')

const port = Number(process.env.MC_PORT || 25596)
const user = process.env.MC_USER || 'ECHO'
const client = mc.createClient({
  host: '127.0.0.1', port, username: user, version: '1.21.1', auth: 'offline'
})
const seen = {}
const log = (ev, d = {}) => console.log(JSON.stringify({ ev, ...d }))

client.on('error', e => log('error', { m: String(e && e.message) }))
client.on('kicked', r => log('kicked', { r: JSON.stringify(r) }))
client.on('end', r => log('end', { r: r || 'quit' }))
client.on('packet', (data, meta) => {
  const n = (meta && meta.name) || 'unnamed'
  seen[n] = (seen[n] || 0) + 1
  if (/slot|window|container|items|creative|inventory|held/i.test(n)) {
    log('pk', { name: n, data: JSON.stringify(data).slice(0, 900) })
  }
})

client.on('login', () => {
  log('login', { version: '1.21.1', user, port })
  const cases = [[36, 1, 64], [37, 396, 1], [45, 1162, 1], [5, 868, 1], [45, 588, 64]]
  let i = 0
  const t = setInterval(() => {
    if (i >= cases.length) { clearInterval(t); return }
    const [slot, id, c] = cases[i++]
    const item = new Item(id, c)
    client.write('set_creative_slot', { slot, item: Item.toNotch(item) })
    log('sent', { slot, id, c })
  }, 700)
  setTimeout(() => {
    log('seen', { seen })
    log('done', { uniquePackets: Object.keys(seen).length })
    client.end()
  }, 7000)
})
