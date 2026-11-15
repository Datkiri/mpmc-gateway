const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25601)
const version = process.env.MC_VERSION || '1.21.4'
const username = process.env.MC_USER || ('MF' + Date.now().toString(36))
const startedAt = Date.now()
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (ev, detail = {}) => {
  if ((ev === 'sample' || ev === 'position_correction' || (ev === 'write' && detail.name === 'teleport_confirm')) && process.env.MC_TRACE !== '1') return
  console.log(JSON.stringify({ ms: Date.now() - startedAt, ev, phase, ...detail }))
}
let phase = 'connect'
let finished = false
let pump
let authority = false
let abilities = null
let tokenId = 0
const checks = []
const errors = []
const positions = []
const abilityEvents = []
const writes = {}
const messages = []
const bot = mineflayer.createBot({ host, port, version, username, auth: 'offline' })
const pos = () => bot.entity ? { x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z } : null
const distance = (a, b) => Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z)
const horizontal = (a, b) => Math.hypot(a.x - b.x, a.z - b.z)

function check (name, ok, detail = {}) {
  if (finished) throw new Error('probe already finished')
  const result = { name, status: ok === null ? 'unavailable' : ok ? 'pass' : 'fail', ...detail }
  checks.push(result)
  log('assertion', result)
  return ok
}

const originalWrite = bot._client.write.bind(bot._client)
bot._client.write = (name, data) => {
  writes[name] = (writes[name] || 0) + 1
  if (name === 'abilities' || name === 'teleport_confirm') log('write', { name, data })
  return originalWrite(name, data)
}
bot._client.on('position', packet => {
  const entry = { phase, ms: Date.now() - startedAt, ...packet }
  positions.push(entry)
  log('position_correction', { packet })
})
bot._client.on('abilities', packet => {
  abilities = packet
  abilityEvents.push({ phase, ms: Date.now() - startedAt, ...packet })
  log('abilities', { packet })
})
bot.on('message', message => {
  const text = message.toString()
  messages.push(text)
  log('server_message', { text })
})
bot.on('error', error => { errors.push(error.message); finish('client_error') })
bot.on('kicked', reason => { errors.push('kicked:' + JSON.stringify(reason)); finish('kicked') })
bot.on('end', reason => {
  if (!finished) { errors.push('unexpected_end:' + reason); finish('unexpected_end') }
})
process.on('unhandledRejection', error => { errors.push(String(error)); finish('unhandled_rejection') })
process.on('uncaughtException', error => { errors.push(String(error)); finish('uncaught_exception') })

async function waitFor (predicate, timeout = 4000) {
  const until = Date.now() + timeout
  while (!finished && Date.now() < until) {
    if (predicate()) return true
    await sleep(50)
  }
  return false
}

function command (text) {
  if (finished) throw new Error('probe already finished')
  log('command', { text })
  bot._client.write('chat_command', { command: text })
}

async function hostPosition (label) {
  if (!authority) return check(label + '_host_position', null, { reason: 'operator command authority unavailable' })
  const target = pos()
  const token = 'MF_' + startedAt.toString(36) + '_' + (++tokenId)
  const marker = messages.length
  command(`execute if entity @s[x=${target.x.toFixed(3)},y=${target.y.toFixed(3)},z=${target.z.toFixed(3)},r=0.75] run tellraw @s {"rawtext":[{"text":"${token}"}]}`)
  const observed = await waitFor(() => messages.slice(marker).some(text => text === token))
  return check(label + '_host_position', observed, { target, tolerance: 0.75, evidence: observed ? 'BDS execute selector accepted position' : 'BDS position marker absent' })
}

async function sample (duration) {
  const samples = []
  const until = Date.now() + duration
  while (!finished && Date.now() < until) {
    const p = pos()
    if (!p || !Object.values(p).every(Number.isFinite)) throw new Error('nonfinite or absent position')
    samples.push(p)
    log('sample', { position: p, onGround: bot.entity.onGround })
    await sleep(50)
  }
  return samples
}

async function motion (label, sprint) {
  phase = label
  const before = pos()
  const correctionStart = positions.length
  pump.control('sprint', sprint)
  pump.control('forward', true)
  const activeStarted = Date.now()
  const samples = await sample(2000)
  const activeElapsed = Date.now() - activeStarted
  const activeDisplacement = horizontal(before, pos())
  const activeSpeed = activeDisplacement / (activeElapsed / 1000)
  log('active_motion_speed', { label, displacement: activeDisplacement, elapsedMs: activeElapsed, blocksPerSecond: activeSpeed })
  pump.control('forward', false)
  pump.control('sprint', false)
  await sample(700)
  const after = pos()
  const displacement = horizontal(before, after)
  check(label + '_displacement', displacement >= 3, { before, after, displacement, minimum: 3, correctionCount: positions.length - correctionStart })
  check(label + '_ground_height', Math.abs(after.y - before.y) < 0.15, { deltaY: after.y - before.y })
  const backwards = samples.slice(1).filter((p, i) => p.z - samples[i].z > 0.5).length
  check(label + '_no_repeated_rewinds', backwards < 2, { backwardsSteps: backwards })
  await hostPosition(label)
  return { displacement, activeSpeed, activeElapsed, activeDisplacement }
}

async function flightStep (label, delta, count, input) {
  phase = label
  if (input) pump.set(input, true)
  const before = pos()
  for (let i = 0; i < count && !finished; i++) {
    bot.entity.position.x += delta.x
    bot.entity.position.y += delta.y
    bot.entity.position.z += delta.z
    bot.entity.onGround = false
    pump.sendPosition()
    await sleep(50)
    log('sample', { position: pos(), clientDrivenFlight: true })
  }
  if (input) pump.set(input, false)
  log('flight_input_released', { input, inputs: { ...pump.state }, position: pos(), velocity: { ...bot.entity.velocity }, physicsEnabled: bot.physicsEnabled })
  await sleep(500)
  const after = pos()
  const step = Math.hypot(delta.x, delta.y, delta.z)
  const expected = step * count
  const progress = ((after.x - before.x) * delta.x + (after.y - before.y) * delta.y + (after.z - before.z) * delta.z) / step
  check(label + '_displacement', progress >= expected * 0.7, { before, after, progress, minimum: expected * 0.7 })
  const hostConfirmed = await hostPosition(label)
  return { progress, hostConfirmed }
}

async function run () {
  phase = 'setup'
  pump = installInputPump(bot)
  pump.start()
  if (!await waitFor(() => pos() && pos().y > -128 && pos().y < 512 && bot.blockAt(bot.entity.position), 15000)) {
    check('spawn_ready', false)
    return finish('spawn_not_ready')
  }
  await sleep(2500)
  const token = 'MF_AUTH_' + startedAt.toString(36)
  command(`tellraw @s {"rawtext":[{"text":"${token}"}]}`)
  authority = await waitFor(() => messages.some(text => text === token), 3000)
  check('host_command_authority', authority ? true : null)
  if (authority) {
    command('gamemode creative @s')
    await sleep(500)
    command('execute in overworld run tp @s 100.5 80 100.5')
    await sleep(3000)
    command('fill 97 79 60 103 79 103 stone')
    await sleep(800)
    command('fill 97 80 60 103 88 103 air')
    await sleep(800)
    command('tp @s 100.5 80 100.5')
    await sleep(1500)
  }
  check('creative_mode', bot.game.gameMode === 'creative', { gamemode: bot.game.gameMode })
  check('may_fly', abilities ? !!(abilities.flags & 4) : null, { abilities })
  check('ability_fields_finite', abilities ? Number.isInteger(abilities.flags) && Number.isFinite(abilities.flyingSpeed) && Number.isFinite(abilities.walkingSpeed) : null, { abilities })
  await bot.look(0, 0, true)
  const start = pos()
  log('fixture', { start, version, host, port, authority })
  let clear = true
  for (let z = 0; z <= 28; z++) {
    const feet = bot.entity.position.offset(0, 0, -z).floored()
    const floor = bot.blockAt(feet.offset(0, -1, 0))
    const lower = bot.blockAt(feet)
    const upper = bot.blockAt(feet.offset(0, 1, 0))
    if (!floor || floor.boundingBox !== 'block' || !lower || lower.boundingBox !== 'empty' || !upper || upper.boundingBox !== 'empty') clear = false
  }
  check('clear_flat_lane', clear ? true : null, { reason: clear ? '28-block lane loaded and clear' : 'walk/sprint bounds may encounter fixture obstacles' })
  if (clear) {
    const walk = await motion('walk', false)
    const sprint = await motion('sprint', true)
    check('active_motion_speed_ratio', sprint.activeSpeed >= walk.activeSpeed * 1.15 && walk.activeDisplacement >= 3, { walk: walk.activeSpeed, sprint: sprint.activeSpeed, ratio: sprint.activeSpeed / walk.activeSpeed, units: 'blocks/second' })
    check('sprint_faster_than_walk', sprint.displacement >= walk.displacement * 1.15 && walk.displacement >= 3, { walk: walk.displacement, sprint: sprint.displacement, metric: 'endpoint_sync_after_settle', note: 'Includes post-release corrections; failure does not alone prove missing sprint acceleration' })
  } else {
    check('walk_sprint_available', null, { reason: 'fixture is not a clear loaded flat lane; movement assertions skipped' })
  }
  phase = 'idle'
  const idleStart = pos()
  await sample(1000)
  check('idle_stable', distance(idleStart, pos()) <= 0.2, { drift: distance(idleStart, pos()) })
  phase = 'jump'
  const ground = pos().y
  pump.control('jump', true)
  const first = await sample(150)
  pump.control('jump', false)
  const rest = await sample(1600)
  const peak = Math.max(...first.concat(rest).map(p => p.y)) - ground
  check('jump_apex', peak >= 0.8, { peak })
  check('jump_landed', Math.abs(pos().y - ground) <= 0.15, { ground, actual: pos().y })
  await hostPosition('jump')

  phase = 'flight_start'
  if (!(abilities && (abilities.flags & 4))) {
    check('flight_available', null, { reason: 'server did not grant may-fly; no flight request sent' })
    return finish('no_flight_permission')
  }
  const flightAbilityStart = abilityEvents.length
  const flightGroundY = pos().y
  bot._client.write('abilities', { flags: 2 })
  await sleep(600)
  log('flight_authority', { abilities, evidence: 'clientbound ability flags; host position checked independently' })
  // Mineflayer startFlying only changes gravity, so drive gradual positions after the wire request.
  bot.physicsEnabled = false
  bot.entity.velocity.set(0, 0, 0)
  const ascent = await flightStep('flight_up', { x: 0, y: 0.15, z: 0 }, 20, 'jump')
  phase = 'flight_hold'
  const hold = pos()
  const holdDuration = Number(process.env.MC_HOLD_MS || 1200)
  const holdStarted = Date.now()
  const holdCorrectionsStart = positions.length
  const holdSamples = []
  while (!finished && Date.now() - holdStarted < holdDuration) {
    holdSamples.push({ ms: Date.now() - holdStarted, position: pos(), inputs: { ...pump.state }, velocity: { ...bot.entity.velocity }, physicsEnabled: bot.physicsEnabled })
    await sleep(50)
  }
  log('flight_hold_diagnostics', { duration: holdDuration, samples: holdSamples, corrections: positions.slice(holdCorrectionsStart) })
  check('flight_hold_inputs_released', Object.values(pump.state).every(value => !value), { inputs: { ...pump.state } })
  const originalHoldEnd = holdSamples.find(s => s.ms >= 1200)
  const originalHoldY = originalHoldEnd ? originalHoldEnd.position.y : pos().y
  check('flight_hold_height', Math.abs(originalHoldY - hold.y) <= 0.2, { drift: originalHoldY - hold.y, duration: 1200, totalObservedDuration: holdDuration })
  const holdConfirmed = await hostPosition('flight_hold')
  if (holdDuration >= 4000) {
    const tail = holdSamples.filter(s => s.ms >= holdDuration - 2100)
    const tailDuration = tail.length > 1 ? tail[tail.length - 1].ms - tail[0].ms : 0
    const tailDrift = tail.length ? Math.max(...tail.map(s => distance(s.position, tail[0].position))) : Infinity
    check('stable_hold_window', tailDuration >= 2000 && tailDrift <= 0.2 && holdConfirmed === true, { duration: tailDuration, drift: tailDrift, hostConfirmed: holdConfirmed, note: 'Independent tail window; original transition drift assertion remains unchanged' })
  }
  const freshFlightAck = abilityEvents.slice(flightAbilityStart).some(event => (event.flags & 2) !== 0)
  const elevatedHold = ascent.hostConfirmed === true && holdConfirmed === true && pos().y - flightGroundY > 1 && Math.abs(pos().y - hold.y) <= 0.2
  check('flight_host_granted', freshFlightAck || elevatedHold ? true : authority ? false : null, { freshFlightAck, elevatedHold, abilityEventsSinceStart: abilityEvents.length - flightAbilityStart })
  await flightStep('flight_forward', { x: 0, y: 0, z: -0.15 }, 20, 'forward')
  if (pos().y - flightGroundY >= 1.5) {
    await flightStep('flight_down', { x: 0, y: -0.1, z: 0 }, 10, 'shift')
  } else {
    check('flight_down_available', null, { reason: 'insufficient altitude above floor; descent would be collision limited', y: pos().y, flightGroundY })
  }
  phase = 'flight_stop'
  bot._client.write('abilities', { flags: 0 })
  bot.physicsEnabled = true
  const stopY = pos().y
  await sample(2000)
  check('flight_stop_falls', stopY - pos().y >= 0.8, { stopY, afterY: pos().y })
  check('flight_stop_lands', bot.entity.onGround === true, { onGround: bot.entity.onGround })
  await hostPosition('flight_stop')
  check('ability_wire_transitions', writes.abilities >= 2, { count: writes.abilities })
  if (pump.modern) {
    check('input_tick_coverage', writes.tick_end > 100 && writes.player_input > 100, { tickEnd: writes.tick_end, playerInput: writes.player_input })
  } else {
    check('legacy_packet_schema', !writes.tick_end && !writes.player_input, { tickEnd: writes.tick_end || 0, playerInput: writes.player_input || 0, note: 'ViaBackwards synthesizes host input; gateway audit must establish host tick coverage' })
  }
  finish('complete')
}

function finish (reason) {
  if (finished) return
  finished = true
  clearTimeout(deadline)
  clearTimeout(loginDeadline)
  if (pump) pump.stop()
  const failed = checks.filter(c => c.status === 'fail').length
  const unavailable = checks.filter(c => c.status === 'unavailable').length
  const complete = reason === 'complete'
  const status = errors.length || failed || !complete ? 'FAIL' : unavailable ? 'INCONCLUSIVE' : 'PASS'
  log('final', { status, reason, host, port, version, username, gatewayArtifactSha256: process.env.MC_GATEWAY_SHA256 || null, authority, checks, errors, writes, positionCorrectionCount: positions.length, correctionCountsByPhase: positions.reduce((counts, p) => { counts[p.phase] = (counts[p.phase] || 0) + 1; return counts }, {}), positionSample: positions.slice(-5), abilityEvents, finalPosition: pos(), caveat: 'Position packets include spawn and server teleports; only gateway audit identifies Bedrock prediction corrections. Client-driven flight requires successful BDS coordinate selectors for acceptance.' })
  try { bot.quit() } catch {}
  setTimeout(() => process.exit(status === 'PASS' ? 0 : status === 'INCONCLUSIVE' ? 2 : 1), 250)
}

const deadline = setTimeout(() => { errors.push('deadline'); finish('timeout') }, Number(process.env.MC_TIMEOUT_MS || 90000))
const loginDeadline = setTimeout(() => { errors.push('pre_spawn_deadline:state=' + bot._client.state); finish('pre_spawn_timeout') }, 45000)
bot.once('spawn', () => {
  clearTimeout(loginDeadline)
  run().catch(error => { errors.push(error.stack || String(error)); finish('run_error') })
})
