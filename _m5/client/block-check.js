const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25569)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'BlockChk'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

bot.on('spawn', async () => {
  await sleep(3000)
  const around = []
  for (let x = -2; x <= 3; x++) {
    around.push(`${x},-60,0=${(bot.blockAt(new Vec3(x, -60, 0)) || {}).name}`)
  }
  log('blocks', { feet: bot.entity.position.floored(), around })
  bot.quit()
  setTimeout(() => process.exit(0), 300)
})
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 300) }); process.exit(1) })
bot.on('end', reason => log('end', { reason: String(reason).slice(0, 160) }))
