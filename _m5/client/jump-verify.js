const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25569)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || ('JumpV' + Math.floor(Math.random() * 1000))

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

bot.on('spawn', async () => {
  const pump = installInputPump(bot)
  pump.start()
  await sleep(1500)
  log('spawn', { y: bot.entity.position.y })

  pump.control('jump', true)
  const samples = []
  for (let i = 0; i < 14; i++) {
    await sleep(100)
    samples.push(Number(bot.entity.position.y.toFixed(3)))
  }
  pump.control('jump', false)
  await sleep(800)
  samples.push(Number(bot.entity.position.y.toFixed(3)))
  log('jump_arc', { samples, max: Math.max(...samples) })

  bot.quit()
  setTimeout(() => process.exit(0), 300)
})
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 300) }); process.exit(1) })
bot.on('end', reason => log('end', { reason: String(reason).slice(0, 160) }))
