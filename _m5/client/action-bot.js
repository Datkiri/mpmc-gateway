const fs = require('fs')
const path = require('path')
const { Vec3 } = require('vec3')
const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'ActionBot'
const holdMs = Number(process.env.HOLD_MS || 3000)
const saneWaitMs = Number(process.env.SANE_WAIT_MS || 15000)
const digRecordFile = path.join(__dirname, 'last-dig.json')

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const pump = installInputPump(bot)

const steps = []
const actions = []
let done = false
let spawnAt = 0
let columnLoads = 0

const sleep = ms => new Promise(r => setTimeout(r, ms))
bot.on('chunkColumnLoad', () => { columnLoads++ })

function pos() {
  if (!bot.entity) return null
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
}

function step(name, detail = {}) {
  const entry = { atMs: Date.now() - t0, name, pos: pos(), ...detail }
  steps.push(entry)
  console.log(JSON.stringify(entry))
}

function finish(code, reason) {
  if (done) return
  done = true
  pump.stop()
  console.log(JSON.stringify({
    ok: code === 0,
    reason,
    spawnMs: spawnAt ? spawnAt - t0 : null,
    gameMode: bot.game?.gameMode ?? null,
    position: pos(),
    health: bot.health ?? null,
    food: bot.food ?? null,
    columnLoads,
    actions,
    steps,
  }))
  setTimeout(() => process.exit(code), 100)
}

function sane() {
  if (!bot.entity) return false
  const y = bot.entity.position.y
  return y > -128 && y < 512
}

async function waitSane(timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (sane()) return true
    await sleep(250)
  }
  return sane()
}

function diggable(block) {
  return block && block.name !== 'air' && block.name !== 'cave_air' &&
    block.name !== 'void_air' && block.name !== 'bedrock' && block.name !== 'barrier'
}

function facingOffset(yaw) {
  return { dx: Math.round(-Math.sin(yaw)), dz: Math.round(Math.cos(yaw)) }
}

async function digBlock(label, dx, dy, dz, allowAir = false) {
  const block = bot.blockAt(bot.entity.position.offset(dx, dy, dz))
  if (block && block.name === 'air' && allowAir) {
    actions.push({ action: label, ok: true, skipped: 'already air', offset: [dx, dy, dz] })
    step('dig_skip_air', { label, offset: [dx, dy, dz] })
    return null
  }
  if (!diggable(block)) {
    actions.push({ action: label, ok: false, reason: 'not diggable', block: block?.name ?? null, offset: [dx, dy, dz] })
    step('dig_skip', { label, block: block?.name ?? null, offset: [dx, dy, dz] })
    return null
  }
  step('dig_target', { label, name: block.name, at: `${block.position.x},${block.position.y},${block.position.z}` })
  try {
    await bot.dig(block, true)
    await sleep(250)
    const after = bot.blockAt(block.position)
    const ok = after?.name === 'air'
    actions.push({ action: label, ok, name: block.name, after: after?.name ?? null })
    step('dig', { label, ok, name: block.name, after: after?.name ?? null })
    const records = readDigRecords()
    records.push({ label, block: block.name, at: [block.position.x, block.position.y, block.position.z] })
    fs.writeFileSync(digRecordFile, JSON.stringify(records.slice(-12)))
    return { block, ok }
  } catch (e) {
    actions.push({ action: label, ok: false, name: block.name, error: e.message })
    step('dig', { label, ok: false, error: e.message })
    return { block, ok: false }
  }
}

function readDigRecords() {
  try {
    return JSON.parse(fs.readFileSync(digRecordFile, 'utf8'))
  } catch {
    return []
  }
}

// Blocks recorded by the previous session must still be air on a fresh join;
// that is the only proof the Bedrock host applied the break.
function verifyEarlierDigs(label) {
  const records = readDigRecords()
  if (records.length === 0) {
    step('dig_verify_empty', { label })
    return
  }
  const results = records.map(r => {
    const block = bot.blockAt(new Vec3(...r.at))
    return { at: r.at, was: r.block, now: block?.name ?? null }
  })
  const ok = results.every(r => r.now === 'air' || r.now === 'cave_air' || r.now === 'void_air')
  actions.push({ action: 'dig_verify', ok, results })
  step('dig_verify', { label, ok, results })
  fs.writeFileSync(digRecordFile, '[]')
}

async function runActions() {
  await sleep(600)
  const gotSane = await waitSane(saneWaitMs)
  step('position_ready', { sane: gotSane })
  if (!gotSane) {
    actions.push({ action: 'position', ok: false, reason: 'spawn position still shifted (+32768)' })
    finish(1, 'spawn position desynced; ViaBedrock spawn offset did not resolve')
    return
  }

  step('start', { onGround: bot.entity.onGround, gameMode: bot.game?.gameMode ?? null })
  verifyEarlierDigs('fresh_join')

  // Without tick_end the Bedrock host receives no PlayerAuthInput at all.
  pump.start()

  // The bot spawns in a 1x1 shaft: open a 2-high tunnel in all four
  // directions so movement is never blocked by an unbroken wall.
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
    for (const dist of [1, 2]) {
      await digBlock(`tunnel_${dx}_${dz}_${dist}_feet`, dx * dist, 0, dz * dist)
      await digBlock(`tunnel_${dx}_${dz}_${dist}_head`, dx * dist, 1, dz * dist)
    }
  }

  // A look right before walking makes the Bedrock side resync position; give
  // the look one full round trip before applying movement input.
  await bot.look(0, 0, true)
  await sleep(800)
  step('look', { yaw: +bot.entity.yaw.toFixed(2) })

  const beforeWalk = pos()
  pump.control('forward', true)
  await sleep(3000)
  pump.control('forward', false)
  await sleep(600)
  const afterWalk = pos()
  const walked = Math.hypot(afterWalk.x - beforeWalk.x, afterWalk.z - beforeWalk.z)
  actions.push({ action: 'walk_forward', ok: walked > 1.5, walked: +walked.toFixed(2), from: beforeWalk, to: afterWalk })
  step('walk_forward', { walked: +walked.toFixed(2), onGround: bot.entity.onGround })

  // Sprint uses the player_input SPRINT flag; entity_action would be read as
  // START_RIDING_JUMP by Java 26.3 and kick the session.
  const beforeSprint = pos()
  pump.control('sprint', true)
  pump.control('forward', true)
  await sleep(2000)
  pump.control('forward', false)
  pump.control('sprint', false)
  await sleep(500)
  const afterSprint = pos()
  const sprinted = Math.hypot(afterSprint.x - beforeSprint.x, afterSprint.z - beforeSprint.z)
  actions.push({ action: 'sprint_back', ok: sprinted > 1.5, sprinted: +sprinted.toFixed(2), from: beforeSprint, to: afterSprint })
  step('sprint_back', { sprinted: +sprinted.toFixed(2) })

  // Jump once in the open; sample during the hop since it lands in ~0.7s.
  const y0 = bot.entity.position.y
  let maxY = y0
  const sampleJump = () => { maxY = Math.max(maxY, bot.entity.position.y) }
  bot.on('physicsTick', sampleJump)
  await pump.pulse('jump', 350)
  await sleep(900)
  bot.removeListener('physicsTick', sampleJump)
  const y1 = bot.entity.position.y
  const jumped = maxY > y0 + 0.3
  actions.push({ action: 'jump', ok: jumped, from: +y0.toFixed(2), max: +maxY.toFixed(2), to: +y1.toFixed(2), onGround: bot.entity.onGround })
  step('jump', { yFrom: +y0.toFixed(2), yMax: +maxY.toFixed(2), yTo: +y1.toFixed(2), ok: jumped })

  // Look around so the player can see the bot turn.
  await bot.look(bot.entity.yaw + Math.PI / 2, 0, true)
  await sleep(400)
  step('look_walked', { yaw: +bot.entity.yaw.toFixed(2) })

  bot.chat(`mpmc bot: đi ${walked.toFixed(1)} block, sprint ${sprinted.toFixed(1)} block, nhảy ${jumped ? 'OK' : 'không'}`)
  step('chat', { message: 'sent' })

  for (let i = 0; i < 3; i++) {
    await bot.look(bot.entity.yaw + Math.PI / 2, 0, true)
    await sleep(600)
  }
  step('wave', { yaw: +bot.entity.yaw.toFixed(2) })
  await sleep(holdMs)

  const allOk = actions.every(a => a.ok)
  finish(allOk ? 0 : 2, allOk ? 'actions complete' : 'some actions failed')
}

bot.on('spawn', () => {
  spawnAt = Date.now()
  step('spawn')
  runActions().catch(e => {
    actions.push({ action: 'script', ok: false, error: e.message })
    finish(1, `action error: ${e.message}`)
  })
})

bot.on('kicked', reason => finish(1, `kicked: ${JSON.stringify(reason)}`))
bot.on('error', err => finish(1, `error: ${err.message}`))
bot.on('end', reason => finish(done ? 0 : 1, `end: ${reason}`))

setTimeout(() => finish(1, 'timeout'), 120000)
