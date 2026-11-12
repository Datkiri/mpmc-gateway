// Force many ITEM_STACK_REQUESTs (each carrying a CraftCreative chain) into
// the SAME Bedrock server tick. The real 1.21.1 GUI produced status 6 =
// InvalidRequestCraftActionType exactly when several clicks landed together;
// spaced-out CLI probes only produced status 49. This driver removes spacing.
//
// Pure CLI: no UI, no rendering, no user input.

const mc = require('minecraft-protocol')
const fs = require('fs')

const version = process.env.MC_VERSION || '1.21.1'
const port = Number(process.env.MC_PORT || 25577)
const user = process.env.MC_USER || ('BURST' + Math.floor(Math.random() * 9000 + 1000))
const tail = Number(process.env.MC_TAIL_MS || 6000)
const caseFile = process.env.MC_CASES || ''
const Item = require('prismarine-item')(version)

const FULL_COMPONENTS = [
  { type: 'max_stack_size', data: 64 },
  { type: 'max_damage', data: 0 },
  { type: 'damage', data: 0 },
  { type: 'rarity', data: 0 },
  { type: 'repair_cost', data: 0 },
  { type: 'unbreakable', data: {} },
  { type: 'custom_data', data: { mpmc_probe: 1 } },
  { type: 'lore', data: [{ text: 'status6-burst' }] },
  { type: 'custom_name', data: { text: 'Burst' } },
  { type: 'enchantment_glint_override', data: false }
]

// Java ids mirror gui-repro.js; they resolve to Bedrock beacon(138),
// damaged_anvil(-960), composter(-213), sniffer_egg(-596), prismarine(168).
const DEFAULT_CASES = [
  { tag: 'b0', slot: 36, id: 396, c: 64, comp: false },
  { tag: 'b1', slot: 37, id: 396, c: 64, comp: true },
  { tag: 'b2', slot: 38, id: 421, c: 1, comp: true },
  { tag: 'b3', slot: 39, id: 1204, c: 1, comp: true },
  { tag: 'b4', slot: 40, id: 588, c: 64, comp: true },
  { tag: 'b5', slot: 41, id: 503, c: 64, comp: true },
  { tag: 'b6', slot: 42, id: 396, c: 64, comp: false },
  { tag: 'b7', slot: 43, id: 421, c: 1, comp: true },
  { tag: 'b8', slot: 44, id: 1204, c: 1, comp: false },
  { tag: 'b9', slot: 45, id: 588, c: 64, comp: true }
]

let cases = DEFAULT_CASES
if (caseFile) cases = JSON.parse(fs.readFileSync(caseFile, 'utf8'))

const client = mc.createClient({ host: '127.0.0.1', port, username: user, version, auth: 'offline' })
const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))

client.on('error', e => log('error', { message: String(e && e.message) }))
client.on('kicked', r => log('kicked', { reason: JSON.stringify(r) }))
client.on('end', r => log('end', { reason: r || 'quit' }))

let clientboundSlots = 0
client.on('packet', (data, meta) => {
  const n = (meta && meta.name) || 'unnamed'
  if (n === 'set_slot' || n === 'container_set_content' || n === 'set_creative_slot' || n === 'window_items') {
    clientboundSlots++
  }
})

client.on('login', () => {
  log('login', { version, user, port, count: cases.length })
  // One synchronous loop: every set_creative_slot is written back-to-back with
  // no timer, so all of them are flushed in effectively one TCP write batch.
  let sent = 0
  for (const k of cases) {
    try {
      const item = new Item(k.id, k.c)
      if (k.comp) item.components = FULL_COMPONENTS.slice()
      const notch = Item.toNotch(item)
      const times = k.n || 2
      for (let t = 0; t < times; t++) {
        client.write('set_creative_slot', { slot: k.slot, item: notch })
        sent++
      }
      log('sent', { tag: k.tag, slot: k.slot, id: k.id, c: k.c, times })
    } catch (e) {
      log('write_error', { tag: k.tag, message: String(e && e.message) })
    }
  }
  log('burst_done', { packets: sent })
  setTimeout(() => {
    log('done', { clientboundSlots })
    client.end()
  }, tail)
})
