// Probe: two Java 1.21.6+ clients in the same Bedrock world to force the server
// to emit locator_bar (Bedrock 0x155 / 341) and observe the translated Java
// tracked_waypoint (0x83) packets. No protocol bytes are injected; this only
// listens to what the Java client receives.
const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25569)
const version = process.env.MC_VERSION || '1.21.6'
const durationMs = Number(process.env.WP_DURATION_MS || 26000)
const t0 = Date.now()
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

const sessions = []

function makeBot (name) {
  const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
  const session = { name, bot, pump: null, tracked: 0, ops: {}, spawned: false }
  sessions.push(session)

  bot._client.on('packet', (data, meta) => {
    const packetName = (meta && meta.name) || (data && data.name)
    if (packetName !== 'tracked_waypoint') return
    session.tracked++
    const op = data && (data.operation !== undefined ? data.operation : data.action)
    session.ops[String(op)] = (session.ops[String(op)] || 0) + 1
    if (session.tracked <= 5) {
      log('tracked_waypoint', {
        bot: name,
        operation: op,
        uuid: data && (data.uuid || data.id),
        style: data && (data.icon_style || data.style),
        keys: Object.keys(data || {}).slice(0, 12),
      })
    }
  })
  bot.on('spawn', () => {
    session.spawned = true
    session.pump = installInputPump(bot)
    session.pump.start()
    log('spawn', { bot: name, y: bot.entity.position.y, gamemode: bot.game.gameMode })
  })
  bot.on('kicked', reason => log('kicked', { bot: name, reason: String(reason).slice(0, 240) }))
  bot.on('error', err => log('error', { bot: name, message: String(err.message).slice(0, 240) }))
  bot.on('end', reason => log('end', { bot: name, reason: String(reason).slice(0, 160) }))
  return session
}

const a = makeBot('WpA')
let b = null
setTimeout(() => { b = makeBot('WpB') }, 4000)

setTimeout(() => {
  if (a.spawned) a.pump.control('forward', true)
  if (b && b.spawned) b.pump.control('back', true)
  log('moving', { a: a.spawned, b: !!(b && b.spawned) })
}, 10000)

setTimeout(() => {
  if (a.pump) a.pump.control('forward', false)
  if (b && b.pump) b.pump.control('back', false)
}, 19000)

setTimeout(() => {
  for (const s of sessions) {
    log('summary', { bot: s.name, spawned: s.spawned, tracked_waypoint: s.tracked, ops: s.ops })
    try { s.bot.quit() } catch {}
  }
  setTimeout(() => process.exit(0), 500)
}, durationMs)
