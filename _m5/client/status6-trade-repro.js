// Reproduction driver for the GUI-only "status 6 / status 60" storm.
//
// Observed on live BDS 1.26.51 / Java 1.21.1 GUI session (vp-live-25569.clean.log):
//   16:52:01  last accepted ITEM_STACK_REQUEST (-25)
//   16:52:04  USE_ITEM_ON with villager_spawn_egg -> villagers spawn
//   16:52:55  first rejected ITEM_STACK_REQUEST (-27, status 6)
//   16:53:05  status 60 = CannotDestroyItem on bare Destroy requests
//   ...every later creative request rejected until disconnect (17:08:46)
//
// Hypothesis: right-clicking the villager makes BDS open the TRADE screen via
// UPDATE_TRADE (id 80), which ViaBedrock consumes as java-counterpart-unmapped.
// BDS then refuses any CraftCreative / bare Destroy ITEM_STACK_REQUEST because
// the active screen is no longer the creative inventory.
//
// This driver reproduces that exact sequence over minecraft-protocol only:
//   1. creative slot 36 = villager_spawn_egg   (should be accepted)
//   2. block_place (USE_ITEM_ON) -> spawn villager
//   3. use_entity on every observed entity id -> open trade screen
//   4. creative clicks + a delete (air) click  (look for status 6 / 60)
//   5. a second wave of creative clicks after a short idle
//
// No raw Minecraft bytes are injected. Env: MC_PORT, MC_USER, MC_VERSION,
// MC_TAIL_MS, MC_IDLE_MS.
const mc = require('minecraft-protocol')
const md = require('minecraft-data')('1.21.1')
const Item = require('prismarine-item')('1.21.1')

const version = process.env.MC_VERSION || '1.21.1'
const port = Number(process.env.MC_PORT || 25570)
const user = process.env.MC_USER || 'A18TRADEREPRO'
const tail = Number(process.env.MC_TAIL_MS || 3000)
const idle = Number(process.env.MC_IDLE_MS || 4000)
const stoneId = md.itemsByName.stone.id
const eggId = md.itemsByName.villager_spawn_egg.id
const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))

const client = mc.createClient({ host: '127.0.0.1', port, username: user, version, auth: 'offline' })
const errors = []
const entities = []
client.on('error', e => { errors.push(String(e && e.message)); log('error', { message: String(e && e.message) }) })
client.on('kicked', r => { errors.push('kicked:' + JSON.stringify(r)); log('kicked', { reason: JSON.stringify(r) }) })
client.on('end', r => log('end', { reason: r || 'quit', errors }))

client.on('packet', (data, meta) => {
  const name = meta && meta.name
  if (!name) return
  if (data && typeof data.entityId === 'number' && /entity|spawn/.test(name)) {
    entities.push({ id: data.entityId, type: data.type, name })
    log('entity_seen', { entityId: data.entityId, type: data.type, name })
  } else if (/trade|merchant|screen|window|open_screen/.test(name)) {
    log('clientbound_ui', { name, state: meta.state, windowId: data && data.windowId })
  }
})

const notch = (id, count) => Item.toNotch(new Item(id, count, 0))
const creative = (slot, id, count, tag) => {
  try {
    client.write('set_creative_slot', { slot, item: notch(id, count) })
    log('sent_creative', { tag, slot, id, count })
  } catch (e) { log('write_error', { tag, message: String(e && e.message) }) }
}
const useEntity = (id, tag) => {
  try {
    client.write('use_entity', { target: id, mouse: 0, hand: 0, sneaking: false })
    log('sent_use_entity', { tag, target: id })
  } catch (e) { log('write_error', { tag, message: String(e && e.message) }) }
}
const at = (ms, fn) => setTimeout(fn, ms)

client.on('login', () => {
  log('login', { version, user, port, eggId, stoneId })

  // Phase 1: baseline creative clicks (BDS must accept these).
  at(1500, () => creative(36, eggId, 1, 'baseline-egg'))
  at(2200, () => creative(37, stoneId, 4, 'baseline-stone'))

  // Phase 2: place the spawn egg. Coordinates measured in the BDS test world.
  at(3200, () => {
    try {
      client.write('block_place', {
        hand: 0, location: { x: 3, y: -60, z: 2 }, direction: 1,
        cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5, insideBlock: false, sequence: 7
      })
      log('sent_block_place', { item: 'villager_spawn_egg', sequence: 7 })
    } catch (e) { log('write_error', { phase: 'block_place', message: String(e && e.message) }) }
  })

  // Phase 3: right-click every entity the Java client has seen (villager included).
  at(5200, () => {
    const ids = [...new Set(entities.map(e => e.id).filter(n => Number.isFinite(n) && n >= 0))]
    log('entity_ids', { ids, total: entities.length })
    ids.slice(-16).forEach((id, i) => at(i * 160, () => useEntity(id, 'trade-open')))
  })

  // Phase 4: creative clicks AFTER the trade interaction. This is where the GUI
  // session started returning status 6 / 60 for every request.
  at(8000 + idle, () => creative(38, stoneId, 1, 'post-trade-A'))
  at(8500 + idle, () => creative(39, md.itemsByName.crafting_table.id, 1, 'post-trade-B'))
  at(9000 + idle, () => creative(38, 0, 1, 'post-trade-delete'))
  at(9500 + idle, () => creative(40, stoneId, 8, 'post-trade-C'))

  at(10500 + idle + tail, () => {
    log('done', { errors, entities: entities.length })
    try { client.end() } catch (e) {}
  })
})
