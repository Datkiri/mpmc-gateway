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

let teleports = 0
let fakeTeleports = 0
bot._client.on('position', p => {
  teleports++
  if (p.teleportId < 0) fakeTeleports++
  log('position', { teleportId: p.teleportId, x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2), flags: p.flags })
})

function pos() {
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
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
  if (!(await waitSane(12000))) {
    log('desync', { pos: pos() })
    process.exit(2)
  }
  log('start', { pos: pos() })
  pump.start()
  await sleep(500)
  const before = pos()
  pump.control('forward', true)
  for (let i = 0; i < 10; i++) {
    await sleep(200)
    log('walk', { pos: pos() })
  }
  pump.control('forward', false)
  await sleep(500)
  log('end', { pos: pos(), walked: +Math.hypot(pos().x - before.x, pos().z - before.z).toFixed(2), teleports, fakeTeleports })
  pump.stop()
  bot.quit()
  setTimeout(() => process.exit(0), 300)
}

bot.on('spawn', () => run().catch(e => { log('error', { error: e.message }); process.exit(1) }))
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 160) }); process.exit(1) })
bot.on('error', err => { log('error', { error: err.message }); process.exit(1) })
setTimeout(() => process.exit(1), 60000)
