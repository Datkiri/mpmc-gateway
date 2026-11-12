// Hypothesis probe (automation 2026-11-12 02:2x):
// memory 2026-11-11 23:10 recorded 20x "status 6 = InvalidRequestCraftActionType"
// in a REAL Java GUI session, alongside "USE_ITEM_ON tx fromItem=-960:0x1".
// All earlier CLI probes only sent SET_CREATIVE_MODE_SLOT (never a block_place
// transaction in the same session) and never reproduced status 6.
//
// This driver reproduces the COMBINATION, purely over minecraft-protocol:
//   - creative slot with the item that maps to Bedrock -960 (damaged_anvil),
//     plus the other runtime-id-negative items from the original GUI session;
//   - a Java 1.21.1 `block_place` (USE_ITEM_ON) transaction shortly after;
//   - a 20 Hz player_input + tick_end pump so ViaBedrock actually emits the
//     Bedrock PlayerAuthInput that carries the queued item interaction.
//
// No raw Minecraft bytes are injected. Env: MC_PORT, MC_USER, MC_VERSION,
// MC_ROUNDS, MC_GAP_MS, MC_TAIL_MS.
const mc = require('minecraft-protocol')
const version = process.env.MC_VERSION || '1.21.1'
const port = Number(process.env.MC_PORT || 25570)
const user = process.env.MC_USER || 'AUT12COMBO'
const rounds = Number(process.env.MC_ROUNDS || 6)
const gap = Number(process.env.MC_GAP_MS || 700)
const tail = Number(process.env.MC_TAIL_MS || 3000)
const Item = require('prismarine-item')(version)
const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))

// Java ids -> Bedrock runtime ids (measured in vp-mpmc13-test2.log / gui-repro):
//   421 -> -960 damaged_anvil, 1204 -> -213 composter, 588 -> -596 sniffer_egg,
//   503 -> 168 prismarine, 396 -> 138 beacon.
const ITEMS = [421, 1204, 588, 503, 396, 421]

const client = mc.createClient({ host: '127.0.0.1', port, username: user, version, auth: 'offline' })
const errors = []
client.on('error', e => { errors.push(String(e && e.message)); log('error', { message: String(e && e.message) }) })
client.on('kicked', r => { errors.push('kicked:' + JSON.stringify(r)); log('kicked', { reason: JSON.stringify(r) }) })
client.on('end', r => log('end', { reason: r || 'quit' }))

let pump = null
client.on('login', () => {
  log('login', { version, user, port, rounds, items: ITEMS })
  // 20 Hz tick pump: without tick_end, ViaBedrock never emits PlayerAuthInput,
  // so no item transaction reaches BDS (memory 2026-11-11 5.1).
  pump = setInterval(() => {
    try {
      client.write('player_input', { inputs: {} })
      client.write('tick_end', {})
    } catch (e) { log('pump_error', { message: String(e && e.message) }) }
  }, 50)

  let i = 0
  const timer = setInterval(() => {
    if (i >= rounds) {
      clearInterval(timer)
      setTimeout(() => {
        if (pump) clearInterval(pump)
        log('done', { errors })
        try { client.end() } catch {}
      }, tail)
      return
    }
    const n = i++
    try {
      const item = new Item(ITEMS[n % ITEMS.length], 1)
      item.components = [
        { type: 'max_stack_size', data: 64 },
        { type: 'damage', data: 0 },
        { type: 'rarity', data: 0 },
        { type: 'repair_cost', data: 0 },
        { type: 'unbreakable', data: {} },
        { type: 'custom_data', data: { mpmc_combo: n } },
        { type: 'enchantment_glint_override', data: false }
      ]
      const notch = Item.toNotch(item)
      // Real GUI emits two SET_CREATIVE_MODE_SLOT for the same slot in one click.
      client.write('set_creative_slot', { slot: 36, item: notch })
      client.write('set_creative_slot', { slot: 36, item: notch })
      setTimeout(() => {
        try {
          client.write('block_place', {
            hand: 0,
            location: { x: 2 + n, y: -60, z: 0 },
            direction: 1,
            cursorX: 0.5, cursorY: 0.5, cursorZ: 0.5,
            insideBlock: false,
            sequence: n
          })
          log('sent', { n, javaId: ITEMS[n % ITEMS.length], slot: 36, useItemOn: true })
        } catch (e) { log('write_error', { n, phase: 'block_place', message: String(e && e.message) }) }
      }, 120)
    } catch (e) { log('write_error', { n, phase: 'creative', message: String(e && e.message) }) }
  }, gap)
})
