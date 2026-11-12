const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25569)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'ProbeB1'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))
const itemName = slot => (slot && slot.name ? `${slot.name} x${slot.count}` : 'empty')

bot.on('spawn', async () => {
  await sleep(2500)
  const slots = []
  for (let i = 36; i <= 44; i++) slots.push([i, itemName(bot.inventory.slots[i])])
  log('inv', { held: itemName(bot.heldItem), slots })
  bot.quit()
  setTimeout(() => process.exit(0), 300)
})
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 300) }); process.exit(1) })
bot.on('end', reason => log('end', { reason: String(reason).slice(0, 160) }))
