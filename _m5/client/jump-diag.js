const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.4'
const maxAttempts = Number(process.env.MAX_ATTEMPTS || 6)

const sleep = ms => new Promise(r => setTimeout(r, ms))

function sane(bot) {
  if (!bot.entity) return false
  const y = bot.entity.position.y
  return y > -128 && y < 512
}

async function attempt(n) {
  const name = `JumpDiag${n}`
  const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
  const t0 = Date.now()
  let spawned = false
  bot.on('spawn', () => { spawned = true })
  bot.on('kicked', r => console.log(JSON.stringify({ ev: 'kicked', n, reason: String(r).slice(0, 120) })))
  bot.on('error', e => console.log(JSON.stringify({ ev: 'error', n, message: e.message })))

  const deadline = Date.now() + 9000
  while (Date.now() < deadline && !sane(bot)) await sleep(250)
  if (!sane(bot)) {
    const y = bot.entity ? +bot.entity.position.y.toFixed(2) : null
    console.log(JSON.stringify({ ev: 'retry', n, y, spawned }))
    bot.quit()
    await sleep(400)
    return null
  }

  await sleep(400)
  console.log(JSON.stringify({ ev: 'sane', n, pos: bot.entity.position, onGround: bot.entity.onGround, gameMode: bot.game?.gameMode }))

  const p0 = bot.entity.position.clone()
  const samples = []
  const onTick = () => {
    if (samples.length < 25) {
      samples.push({ y: +bot.entity.position.y.toFixed(3), vy: +bot.entity.velocity.y.toFixed(3), ground: bot.entity.onGround })
    }
  }
  bot.on('physicsTick', onTick)
  bot.setControlState('jump', true)
  await sleep(400)
  bot.setControlState('jump', false)
  await sleep(1400)
  bot.removeListener('physicsTick', onTick)
  const p1 = bot.entity.position.clone()
  const result = {
    ev: 'jump',
    n,
    yFrom: +p0.y.toFixed(3),
    yTo: +p1.y.toFixed(3),
    maxY: +Math.max(p0.y, ...samples.map(s => s.y)).toFixed(3),
    onGround: bot.entity.onGround,
    samples: samples.slice(0, 12),
  }
  console.log(JSON.stringify(result))
  bot.quit()
  await sleep(400)
  return result
}

;(async () => {
  for (let i = 1; i <= maxAttempts; i++) {
    const r = await attempt(i)
    if (r) { console.log(JSON.stringify({ ev: 'done', attempt: i, jumped: r.yTo > r.yFrom + 0.3 })); process.exit(0) }
  }
  console.log(JSON.stringify({ ev: 'gave_up' }))
  process.exit(1)
})()
