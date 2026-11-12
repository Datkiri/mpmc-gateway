const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.11'
const name = process.env.MC_USER || 'MoveProbe'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))

function snap(tag) {
  if (!bot.entity) return
  const p = bot.entity.position
  const v = bot.entity.velocity
  console.log(JSON.stringify({
    at: Date.now() - t0, tag,
    pos: { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) },
    vel: { x: +v.x.toFixed(2), y: +v.y.toFixed(2), z: +v.z.toFixed(2) },
    yaw: +bot.entity.yaw.toFixed(2), pitch: +bot.entity.pitch.toFixed(2),
    onGround: bot.entity.onGround,
    controls: bot.controlState ? bot.controlState.get ? Object.fromEntries(bot.controlState.get()) : null : null,
  }))
}

let started = false
async function run() {
  if (started) return
  started = true
  await sleep(1500)
  snap('before')
  await bot.look(0, 0, true)
  snap('look-yaw0')
  bot.setControlState('forward', true)
  for (let i = 0; i < 12; i++) {
    await sleep(250)
    snap(`fwd+${(i + 1) * 250}ms`)
  }
  bot.setControlState('forward', false)
  await sleep(1000)
  snap('stopped')

  bot.setControlState('jump', true)
  await sleep(150)
  snap('jump-on')
  bot.setControlState('jump', false)
  for (let i = 0; i < 6; i++) {
    await sleep(250)
    snap(`jump+${(i + 1) * 250}ms`)
  }
  process.exit(0)
}

bot.on('spawn', run)
bot.on('kicked', r => { console.log(JSON.stringify({ ev: 'kicked', reason: r })); process.exit(1) })
bot.on('error', e => { console.log(JSON.stringify({ ev: 'error', message: e.message })); process.exit(1) })
setTimeout(() => { snap('timeout'); process.exit(0) }, 30000)
