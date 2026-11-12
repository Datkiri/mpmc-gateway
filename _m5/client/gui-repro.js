// Reproduce the 1.21.1 real-GUI creative-click behaviour over a CLI socket.
//
// Evidence that motivated this probe (vp-mpmc12-main.log, JavaReal12 @22:17):
//   - every real-GUI click produced TWO SET_CREATIVE_MODE_SLOT packets for the
//     same slot within the same millisecond (request -1 and -3, -7 and -9, ...);
//   - BDS rejected all 20 with status 6 = InvalidRequestCraftActionType;
//   - CLI probes (RaceA*, Probe13Main, gateway-12 lab) sending ONE packet per
//     slot on the SAME jar and SAME BDS were accepted.
//
// This driver varies the two remaining variables: packet pairing and the
// data-component payload a real 1.21.1 GUI client puts on the item stack.
// It is pure CLI: no rendering, no UI interaction, no user input required.

const mc = require('minecraft-protocol')
const fs = require('fs')

const version = process.env.MC_VERSION || '1.21.1'
const port = Number(process.env.MC_PORT || 25571)
const user = process.env.MC_USER || ('GUIR' + Math.floor(Math.random() * 9000 + 1000))
const gap = Number(process.env.MC_GAP_MS || 1200)
const tail = Number(process.env.MC_TAIL_MS || 3000)
const caseFile = process.env.MC_CASES || ''
const Item = require('prismarine-item')(version)

// A real 1.21.1 creative GUI stack carries the default data components, not
// just max_stack_size/damage/rarity. Keep the set conservative so the packet
// encoder accepts it, but broad enough to exercise the NBT/user-data path.
const FULL_COMPONENTS = [
  { type: 'max_stack_size', data: 64 },
  { type: 'max_damage', data: 0 },
  { type: 'damage', data: 0 },
  { type: 'rarity', data: 0 },
  { type: 'repair_cost', data: 0 },
  { type: 'unbreakable', data: {} },
  // enchantments / attribute_modifiers are omitted: their prismarine-item
  // shapes do not match the 1.21.1 wire schema (SizeOf error for undefined).
  { type: 'custom_data', data: { mpmc_probe: 1 } },
  { type: 'lore', data: [{ text: 'gui-repro' }] },
  { type: 'custom_name', data: { text: 'GUI Repro' } },
  { type: 'enchantment_glint_override', data: false }
]

const DEFAULT_CASES = [
  // tag, slot, javaId, count, pair, components, plus the extra packets a real
  // 1.21.1 GUI client emits around a creative-menu click.
  { tag: 'single-plain', slot: 36, id: 396, c: 64, pair: false, comp: false },
  { tag: 'pair-plain', slot: 37, id: 396, c: 64, pair: true, comp: false },
  { tag: 'pair-full', slot: 38, id: 396, c: 64, pair: true, comp: true },
  { tag: 'pair-full-held', slot: 39, id: 421, c: 1, pair: true, comp: true, held: 3 },
  { tag: 'pair-full-pick', slot: 40, id: 1204, c: 1, pair: true, comp: true, pick: 40 },
  { tag: 'pair-window-before', slot: 41, id: 588, c: 64, pair: true, comp: true, window: 41, windowFirst: true },
  { tag: 'pair-window-after', slot: 42, id: 503, c: 64, pair: true, comp: true, window: 42 },
  { tag: 'pair-window-empty', slot: 43, id: 396, c: 64, pair: true, comp: false, window: -999, windowFirst: true }
]

let cases = DEFAULT_CASES
if (caseFile) {
  cases = JSON.parse(fs.readFileSync(caseFile, 'utf8'))
}

const client = mc.createClient({
  host: '127.0.0.1',
  port,
  username: user,
  version,
  auth: 'offline'
})

const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))

client.on('error', e => log('error', { message: String(e && e.message) }))
client.on('kicked', r => log('kicked', { reason: JSON.stringify(r) }))
client.on('end', r => log('end', { reason: r || 'quit' }))

let clientboundSlots = 0
client.on('packet', (data, meta) => {
  const n = (meta && meta.name) || 'unnamed'
  if (n === 'set_slot' || n === 'container_set_content' || n === 'set_creative_slot') {
    clientboundSlots++
  }
})

client.on('login', () => {
  log('login', { version, user, port, count: cases.length, cases: cases.map(x => x.tag) })
  let i = 0
  const timer = setInterval(() => {
    if (i >= cases.length) {
      clearInterval(timer)
      setTimeout(() => {
        log('done', { clientboundSlots })
        client.end()
      }, tail)
      return
    }
    const k = cases[i++]
    try {
      const item = new Item(k.id, k.c)
      if (k.comp) item.components = FULL_COMPONENTS.slice()
      const notch = Item.toNotch(item)
      const windowPacket = k.window === undefined ? null : {
        windowId: 0, stateId: 0, slot: k.window, mouseButton: 0, mode: 0,
        changedSlots: [], cursorItem: notch
      }
      if (k.held !== undefined) {
        client.write('held_item_slot', { slotId: k.held })
      }
      if (k.pick !== undefined) {
        client.write('pick_item', { slot: k.pick })
      }
      if (windowPacket && k.windowFirst) {
        client.write('window_click', windowPacket)
      }
      const times = k.n || (k.pair ? 2 : 1)
      for (let t = 0; t < times; t++) {
        client.write('set_creative_slot', { slot: k.slot, item: notch })
      }
      if (windowPacket && !k.windowFirst) {
        client.write('window_click', windowPacket)
      }
      log('sent', { i: i - 1, tag: k.tag, slot: k.slot, id: k.id, count: k.c, n: times, comp: !!k.comp, held: k.held, pick: k.pick, window: k.window, windowFirst: !!k.windowFirst, bytes: JSON.stringify(notch).length })
    } catch (e) {
      log('write_error', { tag: k.tag, message: String(e && e.message) })
    }
  }, gap)
})
