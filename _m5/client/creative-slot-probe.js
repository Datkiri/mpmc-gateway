// Diagnostic probe: log clientbound container/slot packets after sending one
// serverbound set_creative_slot. Used to compare Java 1.21.1 vs 1.21.4 through
// the Bedrock gateway. No raw Minecraft bytes are injected: minecraft-protocol
// serialises from the selected version's schema.
//
// Env: MC_VERSION, MC_PORT, MC_USER, HOLD_MS, MC_ITEM_IDS, MC_ITEM_COUNT
const mc = require('minecraft-protocol')

const version = process.env.MC_VERSION || '1.21.1'
const port = Number(process.env.MC_PORT || 25569)
const holdMs = Number(process.env.HOLD_MS || 9000)
const Item = require('prismarine-item')(version)
const itemIds = (process.env.MC_ITEM_IDS || '1').split(',').map(s => Number(s.trim())).filter(n => Number.isFinite(n))
const itemCount = Number(process.env.MC_ITEM_COUNT || 16)
const itemMeta = Number(process.env.MC_ITEM_META || 0)
const withComponents = process.env.MC_COMPONENTS === '1'
const componentSet = process.env.MC_COMPONENT_SET || ''
const COMPONENT_SETS = {
  custom: [{ type: 'custom_data', data: { foo: 'bar', num: 7 } }],
  name: [{ type: 'custom_name', data: { text: 'Test' } }],
  ench: [{ type: 'enchantments', data: { levels: { sharpness: 3 } } }],
  damage: [{ type: 'damage', data: 5 }, { type: 'max_damage', data: 100 }],
  blockstate: [{ type: 'block_state', data: { properties: { facing: 'north' } } }],
  lore: [{ type: 'lore', data: [{ text: 'line1' }] }],
  glint: [{ type: 'enchantment_glint_override', data: true }],
  stack: [{ type: 'max_stack_size', data: 64 }, { type: 'damage', data: 0 }, { type: 'rarity', data: 0 }]
}

const client = mc.createClient({
  host: '127.0.0.1',
  port,
  username: process.env.MC_USER || 'CreativeProbe',
  version,
  auth: 'offline'
})

const t0 = Date.now()
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))

client.on('error', e => log('error', { message: String(e && e.message) }))
client.on('kicked', r => log('kicked', { reason: JSON.stringify(r) }))
client.on('end', r => log('end', { reason: r || 'quit' }))

client.on('packet', (data, meta) => {
  const name = meta && meta.name
  if (!name) return
  if (/slot|container|window|creative|held_item|set_/.test(name)) {
    log('packet', { name, state: meta.state, slot: data && data.slot, windowId: data && data.windowId })
  }
})

client.on('login', () => {
  log('login', { version })
  itemIds.forEach((id, i) => {
    setTimeout(() => {
      try {
        const slot = 36 + (i % 9)
        const item = new Item(id, itemCount, itemMeta)
        if (withComponents) {
          // Mimic the default data components a real 1.21.1 GUI creative client sends.
          item.components = [
            { type: 'max_stack_size', data: 64 },
            { type: 'damage', data: 0 },
            { type: 'rarity', data: 0 }
          ]
        }
        if (componentSet && COMPONENT_SETS[componentSet]) {
          item.components = (item.components || []).concat(COMPONENT_SETS[componentSet])
        }
        client.write('set_creative_slot', { slot, item: Item.toNotch(item) })
        log('sent_set_creative_slot', { id, slot, index: i })
      } catch (e) {
        log('write_error', { message: String(e && e.message) })
      }
    }, 1500 + i * 600)
  })
  setTimeout(() => client.end(), Math.max(holdMs, 1500 + itemIds.length * 600 + 2500))
})
