const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25569)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || ('Place' + Math.floor(Math.random() * 1000))

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))
const itemName = slot => (slot && slot.name ? `${slot.name} x${slot.count}` : 'empty')
const blockName = pos => {
  const b = bot.blockAt(pos)
  return b ? `${b.name}@${pos.x},${pos.y},${pos.z}` : 'null'
}

async function waitSane (ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (bot.entity && bot.entity.position.y > -128 && bot.entity.position.y < 512) return true
    await sleep(200)
  }
  return false
}

bot.on('spawn', async () => {
  if (!(await waitSane(15000))) { log('desync', {}); process.exit(2) }
  const pump = installInputPump(bot)
  pump.start()
  await sleep(1200)
  log('spawn', { gameMode: bot.game.gameMode, feet: bot.entity.position.floored(), held: itemName(bot.heldItem) })

  const Item = require('prismarine-item')(bot.version)
  const stone = () => new Item(bot.registry.itemsByName.stone.id, 8)
  await bot.creative.setInventorySlot(36, stone())
  await sleep(1200)
  log('after_set', { slot36: itemName(bot.inventory.slots[36]), held: itemName(bot.heldItem) })

  const feet = bot.entity.position.floored()
  const refPos = feet.offset(2, -1, 0)
  const targetPos = refPos.offset(0, 1, 0)
  log('place_begin', { ref: blockName(refPos), target: blockName(targetPos) })

  try {
    const ref = bot.blockAt(refPos)
    await bot.placeBlock(ref, new Vec3(0, 1, 0))
    log('place_call_ok', {})
  } catch (e) {
    log('place_error', { error: e.message })
  }
  await sleep(2000)
  log('place_result', { target: blockName(targetPos) })

  const digPos = targetPos.offset(0, 0, 1)
  log('dig_begin', { target: blockName(digPos) })
  try {
    const digBlock = bot.blockAt(digPos)
    if (digBlock && digBlock.name !== 'air') {
      await bot.dig(digBlock)
      log('dig_call_ok', {})
    } else {
      log('dig_skip', { reason: 'air' })
    }
  } catch (e) {
    log('dig_error', { error: e.message })
  }
  await sleep(2000)
  log('dig_result', { target: blockName(digPos) })

  bot.quit()
  setTimeout(() => process.exit(0), 300)
})
bot.on('message', msg => log('chat', { text: msg.toString().slice(0, 200) }))
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 300) }); process.exit(1) })
bot.on('end', reason => log('end', { reason: String(reason).slice(0, 160) }))
