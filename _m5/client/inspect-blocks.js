const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.11'
const name = process.env.MC_USER || 'BlockProbe'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))

function pos() {
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
}

let started = false
async function run() {
  if (started) return
  started = true
  await sleep(1200)
  console.log(JSON.stringify({ ev: 'pos', at: Date.now() - t0, pos: pos(), onGround: bot.entity.onGround }))
  const base = bot.entity.position.floored()
  for (let dy = 2; dy >= -1; dy--) {
    const row = []
    for (let dz = -2; dz <= 2; dz++) {
      const cells = []
      for (let dx = -2; dx <= 2; dx++) {
        const b = bot.blockAt(base.offset(dx, dy, dz))
        cells.push((b?.name ?? '?').slice(0, 10))
      }
      row.push(cells.join(' | '))
    }
    console.log(JSON.stringify({ ev: 'layer', dy, rows: row }))
  }

  // Creative flight test (the world reports gameMode creative).
  console.log(JSON.stringify({ ev: 'fly_begin', abilities: bot.entity?.abilities ?? null }))
  try {
    bot.creative.startFlying()
    await sleep(500)
    const y0 = bot.entity.position.y
    bot.setControlState('jump', true)
    await sleep(1200)
    bot.setControlState('jump', false)
    await sleep(500)
    console.log(JSON.stringify({
      ev: 'fly_jump', y0: +y0.toFixed(2), y1: +bot.entity.position.y.toFixed(2),
      flying: bot.entity?.flying ?? null,
    }))
    bot.setControlState('sneak', true)
    await sleep(900)
    bot.setControlState('sneak', false)
    await sleep(400)
    console.log(JSON.stringify({ ev: 'fly_down', y: +bot.entity.position.y.toFixed(2) }))
  } catch (e) {
    console.log(JSON.stringify({ ev: 'fly_error', message: e.message }))
  }
  bot.chat('mpmc inspect done')
  await sleep(800)
  process.exit(0)
}

bot.on('spawn', run)
bot.on('kicked', r => { console.log(JSON.stringify({ ev: 'kicked', reason: r })); process.exit(1) })
bot.on('error', e => { console.log(JSON.stringify({ ev: 'error', message: e.message })); process.exit(1) })
setTimeout(() => process.exit(0), 45000)
