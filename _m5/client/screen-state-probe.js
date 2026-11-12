// Automation 2026-11-12 17:0x — isolate why BDS 1.26.51 starts rejecting EVERY
// ITEM_STACK_REQUEST with status 6 / status 60 after ~2 minutes of a real Java
// 1.21.1 GUI session. Same-session CLI reproduction with a controlled phase list.
//
// Modes (MC_MODE):
//   baseline : only SET_CREATIVE_MODE_SLOT, no screen/entity interaction
//   close    : Java CONTAINER_CLOSE (E key) between two creative clicks
//   entity   : spawn egg USE_ITEM_ON + Java USE_ENTITY, then creative clicks
//   entity-all : like entity but clicks every observed Java entity id (live-entity proof)
//   idle     : creative click, 60 s idle, creative click again
//
// Uses minecraft-protocol's own 1.21.1 schema; no raw bytes injected.
// Env: MC_MODE, MC_PORT, MC_USER, MC_VERSION, MC_TAIL_MS
const mc = require('minecraft-protocol')
const md = require('minecraft-data')('1.21.1')
const Item = require('prismarine-item')('1.21.1')

const version = process.env.MC_VERSION || '1.21.1'
const port = Number(process.env.MC_PORT || 25570)
const mode = process.env.MC_MODE || 'baseline'
const user = process.env.MC_USER || ('A18' + mode)
const tail = Number(process.env.MC_TAIL_MS || 2500)
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
  if (name === 'login') {
    log('login_entity', { entityId: data && data.entityId })
  } else if (name === 'spawn_entity' || name === 'named_entity_spawn' || name === 'add_entity') {
    if (data && typeof data.entityId === 'number') entities.push({ id: data.entityId, type: data.type, name })
    log('entity_spawn', { entityId: data && data.entityId, type: data && data.type, name })
  } else if (/screen|window|container|set_slot/.test(name)) {
    log('packet', { name, state: meta.state, windowId: data && data.windowId, slot: data && data.slot })
  }
})

const notch = (id, count) => Item.toNotch(new Item(id, count, 0))
const creative = (slot, id, count, tag) => {
  try {
    client.write('set_creative_slot', { slot, item: notch(id, count) })
    log('sent_creative', { tag, slot, id, count })
  } catch (e) { log('write_error', { tag, message: String(e && e.message) }) }
}
const phases = []
const at = (ms, tag, fn) => phases.push({ ms, tag, fn })

at(1500, 'creative-A', () => creative(36, mode === 'entity' ? eggId : stoneId, 1, 'A'))
if (mode === 'baseline') {
  at(3000, 'creative-B', () => creative(36, stoneId, 1, 'B'))
  at(5000, 'creative-C', () => creative(37, stoneId, 4, 'C'))
  at(7000, 'creative-D', () => creative(36, stoneId, 2, 'D'))
} else if (mode === 'close') {
  at(3000, 'close_window', () => { try { client.write('close_window', { windowId: 0 }); log('sent_close_window') } catch (e) { log('write_error', { message: String(e && e.message) }) } })
  at(4200, 'creative-B', () => creative(36, stoneId, 1, 'B'))
  at(5400, 'creative-C', () => creative(37, stoneId, 4, 'C'))
  at(7600, 'creative-D', () => creative(36, stoneId, 2, 'D'))
} else if (mode === 'entity') {
  at(3000, 'block_place_egg', () => {
    try {
      client.write('block_place', {
        hand: 0, location: { x: 3, y: -60, z: 2 }, direction: 1,
        cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5, insideBlock: false, sequence: 7
      })
      log('sent_block_place', { item: 'villager_spawn_egg', sequence: 7 })
    } catch (e) { log('write_error', { message: String(e && e.message) }) }
  })
  at(4800, 'use_entity', () => {
    const target = entities.length ? entities[0].id : -1
    try {
      client.write('use_entity', { target, mouse: 0, hand: 0, sneaking: false })
      log('sent_use_entity', { target, entities: entities.length })
    } catch (e) { log('write_error', { message: String(e && e.message) }) }
  })
  at(6600, 'creative-B', () => creative(36, stoneId, 1, 'B'))
  at(8000, 'creative-C', () => creative(37, stoneId, 4, 'C'))
  at(9800, 'creative-D', () => creative(36, stoneId, 2, 'D'))
} else if (mode === 'entity-all') {
  // Click every entity id the Java client has observed, spaced out, so at least one
  // maps to a live Bedrock entity and proves INTERACT -> INVENTORY_TRANSACTION end to end.
  at(1500, 'creative-A', () => creative(36, eggId, 1, 'A'))
  at(3000, 'block_place_egg', () => {
    try {
      client.write('block_place', {
        hand: 0, location: { x: 3, y: -60, z: 2 }, direction: 1,
        cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5, insideBlock: false, sequence: 7
      })
      log('sent_block_place', { item: 'villager_spawn_egg', sequence: 7 })
    } catch (e) { log('write_error', { message: String(e && e.message) }) }
  })
  at(4800, 'use_entity_all', () => {
    const ids = [...new Set(entities.map(e => e.id).filter(n => Number.isFinite(n) && n >= 0))]
    log('use_entity_all_ids', { ids, total: entities.length })
    ids.slice(-14).forEach((id, i) => {
      setTimeout(() => {
        try {
          client.write('use_entity', { target: id, mouse: 0, hand: 0, sneaking: false })
          log('sent_use_entity', { target: id })
        } catch (e) { log('write_error', { message: String(e && e.message) }) }
      }, i * 180)
    })
  })
  at(10000, 'creative-B', () => creative(36, stoneId, 1, 'B'))
  at(11500, 'creative-C', () => creative(37, stoneId, 4, 'C'))
  at(13000, 'creative-D', () => creative(36, stoneId, 2, 'D'))
} else if (mode === 'idle') {
  at(60000, 'creative-B', () => creative(36, stoneId, 1, 'B'))
} else if (mode === 'gui-like') {
  // Mirror the live GUI timeline: early creative clicks, a burst of block_place,
  // then a long idle before the next creative click (GUI: 16:52:01 accept -> 16:52:55 reject).
  at(1500, 'creative-A', () => creative(36, stoneId, 1, 'A'))
  for (let i = 0; i < 60; i++) {
    at(3000 + i * 100, 'use-item-on-' + (i + 1), () => {
      try {
        client.write('block_place', {
          hand: 0, location: { x: 3 + (i % 3), y: -60, z: 2 }, direction: 1,
          cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5, insideBlock: false, sequence: i + 1
        })
      } catch (e) { /* ignore */ }
    })
  }
  at(12000, 'creative-A2', () => creative(37, stoneId, 4, 'A2'))
  at(60000, 'creative-B', () => creative(36, stoneId, 2, 'B'))
  at(63000, 'creative-C', () => creative(38, stoneId, 3, 'C'))
  at(66000, 'creative-D', () => creative(36, stoneId, 1, 'D'))
} else if (mode === 'burst20') {
  // 20 creative clicks, no other interaction. If BDS starts rejecting at a fixed
  // ordinal, the GUI's 13 accepted / 108 rejected split is a request-id or rate limit.
  for (let i = 0; i < 20; i++) {
    at(1500 + i * 650, 'creative-' + (i + 1), () => creative(36, stoneId, (i % 4) + 1, 'N' + (i + 1)))
  }
} else if (mode === 'flood') {
  // Mimic a real GUI holding right-click: ~80 USE_ITEM_ON in 8 s, then creative clicks.
  for (let i = 0; i < 80; i++) {
    at(1500 + i * 100, 'use-item-on-' + (i + 1), () => {
      try {
        client.write('block_place', {
          hand: 0, location: { x: 3 + (i % 3), y: -60, z: 2 }, direction: 1,
          cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5, insideBlock: false, sequence: i + 1
        })
      } catch (e) { /* ignore */ }
    })
  }
  at(10000, 'creative-B', () => creative(36, stoneId, 1, 'B'))
  at(11000, 'creative-C', () => creative(37, stoneId, 4, 'C'))
  at(12000, 'creative-D', () => creative(36, stoneId, 2, 'D'))
}

let pump = null
client.on('login', () => {
  log('login', { version, port, user, mode, stoneId, eggId })
  pump = setInterval(() => {
    try { client.write('player_input', { inputs: {} }); client.write('tick_end', {}) }
    catch (e) { /* ignore */ }
  }, 50)
  phases.forEach(p => setTimeout(() => { log('phase', { tag: p.tag }); p.fn() }, p.ms))
  const last = phases.length ? phases[phases.length - 1].ms : 1000
  setTimeout(() => {
    if (pump) clearInterval(pump)
    log('done', { errors, entities: entities.length })
    try { client.end() } catch {}
  }, last + tail)
})
