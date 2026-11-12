const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.11'
const name = process.env.MC_USER || 'mpmc_bot'
const holdMs = Number(process.env.HOLD_MS || 4000)

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })

let loginAt = 0
let spawnAt = 0
let done = false

function finish(code, reason) {
  if (done) return
  done = true
  console.log(JSON.stringify({
    ok: code === 0,
    reason,
    loginMs: loginAt ? loginAt - t0 : null,
    spawnMs: spawnAt ? spawnAt - t0 : null,
    position: bot.entity ? {
      x: Number(bot.entity.position.x.toFixed(2)),
      y: Number(bot.entity.position.y.toFixed(2)),
      z: Number(bot.entity.position.z.toFixed(2)),
    } : null,
    health: bot.health ?? null,
    food: bot.food ?? null,
    players: Object.keys(bot.players),
    chunks: Object.keys(bot.world?.columns || {}).length,
  }))
  setTimeout(() => process.exit(code), 100)
}

bot.on('login', () => {
  loginAt = Date.now()
  console.log(`login ${loginAt - t0}ms`)
})

bot.on('spawn', async () => {
  spawnAt = Date.now()
  console.log(`spawn ${spawnAt - t0}ms`)
  try {
    bot.chat('mpmc m5 hello')
    bot.setControlState('forward', true)
    await new Promise(r => setTimeout(r, 1000))
    bot.setControlState('forward', false)
    await new Promise(r => setTimeout(r, holdMs))
    finish(0, 'spawned and played')
  } catch (e) {
    finish(1, `post-spawn error: ${e.message}`)
  }
})

bot.on('kicked', reason => finish(1, `kicked: ${JSON.stringify(reason)}`))
bot.on('error', err => finish(1, `error: ${err.message}`))
bot.on('end', reason => finish(0, `end: ${reason}`))

setTimeout(() => finish(1, 'timeout'), 60000)
