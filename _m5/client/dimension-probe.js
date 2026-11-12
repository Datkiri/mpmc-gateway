// Force a Bedrock dimension change through the patched gateway.
// Requires a dedicated BDS with default-player-permission-level=operator.
// Only uses normal Java chat/command packets - no raw byte injection.
//
// Strategy: do NOT walk into the portal (the bot's yaw is unreliable). Instead
// place a portal block and teleport the player straight into it with `tp @s`,
// then keep the Java tick pump running while Bedrock decides to change dimension.
// `WP_COMMANDS` can override the command sequence, e.g. to build a full obsidian
// frame or to test the death/respawn path with `kill @s`.
const mineflayer = require('mineflayer')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25570)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || 'DimProbe'
// Java chat_command carries the command WITHOUT the leading slash; ViaBedrock
// prepends '/' when translating to Bedrock COMMAND_REQUEST. Keeping commands
// slash-free also avoids Git Bash MSYS path conversion of `/foo`.
const commands = (process.env.WP_COMMANDS || 'setblock 0 -60 0 portal|tp @s 0 -60 0').split('|').filter(Boolean)
const gapMs = Number(process.env.WP_GAP_MS || 1800)
// BDS only registers the player into the command context after "Player Spawned".
// A command sent immediately after mineflayer's spawn event fails with
// "No targets matched selector" even for @s, so wait before the first one.
const preDelayMs = Number(process.env.WP_PRE_DELAY_MS || 2500)
const walkMs = Number(process.env.WP_WALK_MS || 0)
const holdMs = Number(process.env.WP_HOLD_MS || 14000)
const t0 = Date.now()
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
let pump = null
let sane = false
const packetCounts = Object.create(null)
const dimensionSnapshots = []
const pos = () => bot.entity ? {
  x: +bot.entity.position.x.toFixed(2),
  y: +bot.entity.position.y.toFixed(2),
  z: +bot.entity.position.z.toFixed(2),
} : null
const snapshot = tag => {
  const d = bot.game.dimension
  dimensionSnapshots.push({ tag, dimension: d, pos: pos() })
  return d
}

bot._client.on('packet', (data, meta) => {
  const n = (meta && meta.name) || (data && data.name) || '?'
  packetCounts[n] = (packetCounts[n] || 0) + 1
  if (n === 'respawn' || n === 'login' || n === 'change_dimension') {
    log('java_packet', {
      name: n,
      keys: Object.keys(data || {}).slice(0, 16),
      dimension: data && data.dimension,
      gamemode: data && (data.gamemode !== undefined ? data.gamemode : data.gameMode),
    })
  }
})
bot.on('message', msg => log('server_message', { text: String(msg.toString ? msg.toString() : msg).slice(0, 220) }))
bot.on('respawn', () => log('mineflayer_respawn', { pos: pos(), dimension: snapshot('mineflayer_respawn') }))
bot.on('kicked', reason => log('kicked', { reason: String(reason).slice(0, 300) }))
bot.on('error', err => log('error', { message: String(err.message).slice(0, 300) }))
bot.on('end', reason => log('end', { reason: String(reason).slice(0, 200) }))

bot.on('spawn', async () => {
  pump = installInputPump(bot)
  pump.start()
  for (let i = 0; i < 40 && bot.entity.position.y <= -128; i++) await sleep(250)
  sane = bot.entity.position.y > -128 && bot.entity.position.y < 512
  log('spawn', { sane, pos: pos(), dimension: snapshot('spawn'), gamemode: bot.game.gameMode })

  await sleep(preDelayMs)
  log('pre_delay_done', { pos: pos(), dimension: bot.game.dimension })
  for (const cmd of commands) {
    log('command', { cmd })
    try { bot._client.write('chat_command', { command: cmd }) }
    catch (e) { log('command_error', { message: String(e.message) }) }
    await sleep(gapMs)
    log('after_command', { pos: pos(), dimension: snapshot('after:' + cmd) })
  }

  if (walkMs > 0) {
    log('walk_start', { pos: pos() })
    pump.control('forward', true)
    await sleep(walkMs)
    pump.control('forward', false)
    log('walk_end', { pos: pos(), dimension: snapshot('walk_end') })
  }

  // Portal transition needs a few seconds of Bedrock ticks; keep pumping input.
  await sleep(holdMs)
  snapshot('final')
  log('final', {
    sane,
    pos: pos(),
    dimension: bot.game.dimension,
    dimensionChanged: dimensionSnapshots.some(s => s.dimension !== dimensionSnapshots[0].dimension),
    dimensionSnapshots,
    gamemode: bot.game.gameMode,
    packetCounts,
    stillConnected: !bot._client.ended,
  })
  bot.quit()
  setTimeout(() => process.exit(0), 400)
})
