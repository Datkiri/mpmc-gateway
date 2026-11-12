const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'ActionBot'
const holdMs = Number(process.env.HOLD_MS || 4000)

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const pump = installInputPump(bot)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

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

const BAD = new Set(['air', 'cave_air', 'void_air', 'bedrock', 'barrier'])

async function dig(label, dx, dy, dz) {
  const block = bot.blockAt(bot.entity.position.offset(dx, dy, dz))
  if (!block || BAD.has(block.name)) { log('dig_skip', { label, block: block?.name ?? null }); return }
  try {
    await bot.dig(block, true)
    await sleep(180)
    const after = bot.blockAt(block.position)
    log('dig', { label, block: block.name, at: [block.position.x, block.position.y, block.position.z], after: after?.name ?? null })
  } catch (e) {
    log('dig_error', { label, error: e.message })
  }
}

function facing(yaw) {
  return { dx: Math.round(-Math.sin(yaw)), dz: Math.round(Math.cos(yaw)) }
}

async function walk(flag, ms, sprint) {
  const from = pos()
  pump.control('forward', flag === 'forward')
  pump.control('backward', flag === 'backward')
  if (sprint) pump.control('sprint', true)
  await sleep(ms)
  pump.control('forward', false)
  pump.control('backward', false)
  if (sprint) pump.control('sprint', false)
  await sleep(400)
  const to = pos()
  const dist = Math.hypot(to.x - from.x, to.z - from.z)
  log('walk', { flag, sprint, dist: +dist.toFixed(2), from, to })
  return dist
}

async function jump(label) {
  const y0 = bot.entity.position.y
  let maxY = y0
  const sample = () => { maxY = Math.max(maxY, bot.entity.position.y) }
  bot.on('physicsTick', sample)
  await pump.pulse('jump', 300)
  await sleep(700)
  bot.removeListener('physicsTick', sample)
  log('jump', { label, from: +y0.toFixed(2), max: +maxY.toFixed(2), jumped: maxY > y0 + 0.3 })
}

async function run() {
  if (!(await waitSane(15000))) {
    log('desync', { pos: pos() })
    process.exit(2)
  }
  log('start', { pos: pos(), gameMode: bot.game?.gameMode })
  pump.start()
  await sleep(600)

  await bot.look(-Math.PI / 2, 0, true)
  await sleep(400)
  const dir = facing(bot.entity.yaw)
  log('face', { yaw: +bot.entity.yaw.toFixed(2), dir })

  for (let d = 1; d <= 7; d++) {
    await dig(`tunnel_${d}_feet`, dir.dx * d, 0, dir.dz * d)
    await dig(`tunnel_${d}_head`, dir.dx * d, 1, dir.dz * d)
  }
  await sleep(600)

  let walked = 0
  let sprinted = 0
  for (let lap = 1; lap <= 2; lap++) {
    walked += await walk('forward', 2500, false)
    await jump(`lap${lap}`)
    sprinted += await walk('backward', 2000, true)
    sprinted += await walk('forward', 1800, true)
    await walk('backward', 1800, false)
    bot.chat(`mpmc bot: vòng ${lap} xong, tại ${JSON.stringify(pos())}`)
    log('chat', { lap, pos: pos() })
    await sleep(600)
  }
  const final = pos()
  bot.chat(`mpmc bot: đi ${walked.toFixed(1)}b, sprint ${sprinted.toFixed(1)}b, đập block OK`)
  log('chat', { pos: final })
  await sleep(holdMs)
  pump.stop()
  bot.quit()
  setTimeout(() => process.exit(0), 300)
}

bot.on('spawn', () => run().catch(e => { log('error', { error: e.message }); process.exit(1) }))
bot.on('kicked', r => { log('kicked', { reason: String(r).slice(0, 200) }); process.exit(1) })
bot.on('error', e => { log('error', { error: e.message }); process.exit(1) })
setTimeout(() => process.exit(1), 180000)
