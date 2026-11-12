const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'ActionBot'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

const trueWrite = bot._client.write.bind(bot._client)
bot._client.write = function (n, p) {
  if (n === 'player_input' || n === 'tick_end') {
    log('write', { packet: n, inputs: n === 'player_input' ? p && p.inputs : undefined })
  }
  return trueWrite(n, p)
}
const pump = installInputPump(bot)

async function waitSane(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (bot.entity && bot.entity.position.y > -128 && bot.entity.position.y < 512) return true
    await sleep(200)
  }
  return false
}

async function run() {
  if (!(await waitSane(12000))) { log('desync'); process.exit(2) }
  log('start')
  pump.start()
  await sleep(600)
  pump.control('forward', true)
  await sleep(1500)
  pump.control('forward', false)
  await sleep(400)
  pump.stop()
  bot.quit()
  setTimeout(() => process.exit(0), 300)
}

bot.on('spawn', () => run().catch(e => { log('error', { error: e.message }); process.exit(1) }))
bot.on('kicked', r => { log('kicked', { reason: String(r).slice(0, 160) }); process.exit(1) })
bot.on('error', e => { log('error', { error: e.message }); process.exit(1) })
setTimeout(() => process.exit(1), 60000)
