const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'ActionBot'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const pump = installInputPump(bot)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

function pos() {
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
}
function itemName(slot) {
  const it = slot || {}
  return it.name ? `${it.name} x${it.count}` : 'empty'
}
async function waitSane(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (bot.entity && bot.entity.position.y > -128 && bot.entity.position.y < 512) return true
    await sleep(200)
  }
  return false
}

async function run() {
  if (!(await waitSane(15000))) {
    log('desync', { pos: pos() })
    process.exit(2)
  }
  log('spawn', { pos: pos(), gameMode: bot.game.gameMode, held: itemName(bot.heldItem) })
  pump.start()
  await sleep(800)

  const Item = require('prismarine-item')(bot.version)
  const Vec3 = require('vec3')

  log('creative_set_begin', { slot: 36, before: itemName(bot.inventory.slots[36]) })
  try {
    await bot.creative.setInventorySlot(36, new Item(bot.registry.itemsByName.stone.id, 1))
  } catch (e) {
    log('creative_set_error', { error: e.message })
  }
  await sleep(1800)
  log('creative_set_result', { slot: 36, after: itemName(bot.inventory.slots[36]), held: itemName(bot.heldItem) })

  bot.setQuickBarSlot(0)
  await sleep(400)
  log('held_after_select', { held: itemName(bot.heldItem) })

  const feet = bot.entity.position.floored()
  const base = bot.blockAt(feet.offset(-1, -1, 0))
  const targetPos = feet.offset(-1, 0, 0)
  log('place_begin', { base: base && base.name, target: { x: targetPos.x, y: targetPos.y, z: targetPos.z }, blockBefore: (bot.blockAt(targetPos) || {}).name })
  if (base && base.boundingBox === 'block') {
    try {
      await bot.placeBlock(base, new Vec3(0, 1, 0))
      log('place_call_ok', {})
    } catch (e) {
      log('place_error', { error: e.message })
    }
  } else {
    log('place_no_base', { base: base && base.name })
  }
  await sleep(1800)
  log('place_result', { blockAfter: (bot.blockAt(targetPos) || {}).name, held: itemName(bot.heldItem) })

  log('give_begin', {})
  bot.chat('/give @s stone 1')
  await sleep(2500)
  const stoneSlot = bot.inventory.slots.findIndex((s, i) => i >= 36 && s && s.name === 'stone')
  log('give_result', { stoneIndex: stoneSlot, slot36: itemName(bot.inventory.slots[36]), held: itemName(bot.heldItem) })
  if (stoneSlot >= 36 && stoneSlot < 45) {
    bot.setQuickBarSlot(stoneSlot - 36)
    await sleep(400)
    log('give_held', { held: itemName(bot.heldItem) })
    if (base && base.boundingBox === 'block') {
      try {
        await bot.placeBlock(base, new Vec3(0, 1, 0))
        log('place2_call_ok', {})
      } catch (e) {
        log('place2_error', { error: e.message })
      }
    }
    await sleep(1800)
    log('place2_result', { blockAfter: (bot.blockAt(targetPos) || {}).name, held: itemName(bot.heldItem) })
  }

  pump.stop()
  bot.quit()
  setTimeout(() => process.exit(0), 300)
}

bot.on('spawn', () => run().catch(e => { log('error', { error: e.stack || e.message }); process.exit(1) }))
bot.on('message', (msg) => log('chat', { text: msg.toString().slice(0, 200) }))
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 200) }); process.exit(1) })
bot.on('end', reason => log('end', { reason: String(reason).slice(0, 120) }))
