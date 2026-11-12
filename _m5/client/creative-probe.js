const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25568)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || ('Probe' + Math.floor(Math.random() * 1000))
const mode = process.env.PROBE_MODE || 'open'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))
const itemName = slot => (slot && slot.name ? `${slot.name} x${slot.count}` : 'empty')

let started = false

async function waitSane (ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (bot.entity && bot.entity.position.y > -128 && bot.entity.position.y < 512) return true
    await sleep(200)
  }
  return false
}

async function openInventoryScreen () {
  log('open_begin', { window: bot.currentWindow ? bot.currentWindow.id : 0 })
  try {
    // window_click on an empty main-inventory slot; ViaBedrock answers with Interact(OpenInventory)
    await bot.clickWindow(9, 0, 0)
    log('open_click_ok', {})
  } catch (e) {
    log('open_click_error', { error: e.message })
  }
  await sleep(1200)
}

async function setSlot (slot, item) {
  log('creative_set_begin', { slot, before: itemName(bot.inventory.slots[slot]) })
  try {
    await bot.creative.setInventorySlot(slot, item)
    log('creative_set_call_ok', {})
  } catch (e) {
    log('creative_set_error', { error: e.message })
  }
  await sleep(1800)
  log('creative_set_result', { slot, after: itemName(bot.inventory.slots[slot]) })
}

async function run () {
  if (!(await waitSane(15000))) { log('desync', {}); process.exit(2) }
  log('spawn', { mode, gameMode: bot.game.gameMode, held: itemName(bot.heldItem) })
  await sleep(800)

  const Item = require('prismarine-item')(bot.version)
  const stone = () => new Item(bot.registry.itemsByName.stone.id, 1)

  if (mode === 'open' || mode === 'open-twice') {
    await openInventoryScreen()
  }

  await setSlot(36, stone())
  if (mode === 'open-twice') {
    await setSlot(37, stone())
  }

  bot.quit()
  setTimeout(() => process.exit(0), 300)
}

bot.on('spawn', () => {
  if (started) return
  started = true
  run().catch(e => { log('error', { error: e.stack || e.message }); process.exit(1) })
})
bot.on('windowOpen', w => log('window_open', { id: w.id, type: w.type, slots: w.slots.length }))
bot.on('message', msg => log('chat', { text: msg.toString().slice(0, 200) }))
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 300) }); process.exit(1) })
bot.on('end', reason => log('end', { reason: String(reason).slice(0, 160) }))
