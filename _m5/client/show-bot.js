const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'ActionBot'
const cycles = Number(process.env.CYCLES || 8)

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const pump = installInputPump(bot)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

function pos() {
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
}

function sane() {
  if (!bot.entity) return false
  const y = bot.entity.position.y
  return y > -128 && y < 512
}

async function waitSane(ms) {
  const end = Date.now() + ms
  while (Date.now() < end && !sane()) await sleep(250)
  return sane()
}

function facing(yaw) {
  return { dx: Math.round(-Math.sin(yaw)), dz: Math.round(Math.cos(yaw)) }
}

async function digAhead(label) {
  const { dx, dz } = facing(bot.entity.yaw)
  for (const dy of [0, 1]) {
    const block = bot.blockAt(bot.entity.position.offset(dx, dy, dz))
    if (!block || ['air', 'cave_air', 'void_air', 'bedrock', 'barrier'].includes(block.name)) continue
    try {
      await bot.dig(block, true)
      log('dig', { label, block: block.name, at: `${block.position.x},${block.position.y},${block.position.z}`, pos: pos() })
      await sleep(350)
    } catch (e) {
      log('dig_error', { label, error: e.message })
    }
    return
  }
}

async function jumpOnce(label) {
  const y0 = bot.entity.position.y
  let maxY = y0
  const sample = () => { maxY = Math.max(maxY, bot.entity.position.y) }
  bot.on('physicsTick', sample)
  await pump.pulse('jump', 300)
  await sleep(800)
  bot.removeListener('physicsTick', sample)
  log('jump', { label, from: +y0.toFixed(2), max: +maxY.toFixed(2), jumped: maxY > y0 + 0.3, pos: pos() })
}

async function walk(control, ms, sprint = false) {
  const before = pos()
  if (sprint) pump.control('sprint', true)
  pump.control(control, true)
  await sleep(ms)
  pump.control(control, false)
  if (sprint) pump.control('sprint', false)
  await sleep(400)
  const after = pos()
  const dist = Math.hypot(after.x - before.x, after.z - before.z)
  log('walk', { control, sprint, dist: +dist.toFixed(2), from: before, to: after })
  return dist
}

async function run() {
  await sleep(500)
  if (!(await waitSane(12000))) {
    log('desync', { pos: pos(), note: 'fresh identity stuck at y~32768; reconnect with same name' })
    bot.quit()
    setTimeout(() => process.exit(2), 100)
    return
  }
  pump.start()
  log('ready', { pos: pos(), gameMode: bot.game?.gameMode, health: bot.health, food: bot.food })
  bot.chat('mpmc bot đã vào world, chuẩn bị di chuyển và đập block')

  for (let i = 1; i <= cycles; i++) {
    log('cycle', { i, pos: pos() })
    await digAhead(`cycle${i}`)
    const walkDist = await walk('forward', 1200)
    await jumpOnce(`fwd${i}`)
    await walk('back', 1200)
    await bot.look(bot.entity.yaw + Math.PI / 2, 0, true)
    await sleep(500)
    await jumpOnce(`turn${i}`)
    await digAhead(`turn${i}`)
    if (i % 2 === 0) {
      const sprintDist = await walk('forward', 1200, true)
      log('sprint', { dist: +sprintDist.toFixed(2), walkDist: +walkDist.toFixed(2) })
      bot.chat(`mpmc bot: vòng ${i} xong, vị trí ${JSON.stringify(pos())}`)
      await sleep(400)
    }
  }

  bot.chat('mpmc bot xong phần trình diễn, tạm biệt')
  pump.stop()
  log('done', { pos: pos(), health: bot.health, food: bot.food })
  await sleep(1500)
  bot.quit()
  setTimeout(() => process.exit(0), 200)
}

bot.on('spawn', () => { run().catch(e => { log('error', { error: e.message }); process.exit(1) }) })
bot.on('kicked', reason => { log('kicked', { reason: String(reason).slice(0, 200) }); process.exit(1) })
bot.on('error', err => { log('error', { error: err.message }); process.exit(1) })
setTimeout(() => { log('timeout'); process.exit(1) }, 240000)
