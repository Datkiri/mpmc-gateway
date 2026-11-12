// Broad packet-audit probe: exercises as many Java serverbound packets as
// mineflayer can produce, and forces BDS to emit a wide range of clientbound
// Bedrock packets. No raw Minecraft bytes are injected; every packet is
// produced by mineflayer / node-minecraft-protocol or by a BDS command.
//
// Env: MC_HOST, MC_PORT, MC_VERSION, MC_USER, WP_PRE_DELAY_MS, WP_GAP_MS,
//      WP_HOLD_MS (total session), WP_ACTIONS (0|1)
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const { installInputPump } = require('./bedrock-input')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25569)
const version = process.env.MC_VERSION || '1.21.4'
const name = process.env.MC_USER || ('Audit' + Math.floor(Math.random() * 10000))
const preDelayMs = Number(process.env.WP_PRE_DELAY_MS || 2500)
const gapMs = Number(process.env.WP_GAP_MS || 700)
const holdMs = Number(process.env.WP_HOLD_MS || 6000)
const doActions = process.env.WP_ACTIONS !== '0'

// BDS commands are slash-free: Java chat_command carries the command without
// the leading slash and ViaBedrock prepends it for Bedrock COMMAND_REQUEST.
// Keeping them slash-free also avoids Git Bash MSYS path conversion.
const COMMANDS = [
  'list',
  'say audit-say-ok',
  'tellraw @a {"rawtext":[{"text":"audit-tellraw-ok"}]}',
  'time set day',
  'weather rain 60',
  'weather clear',
  'gamerule dodaylightcycle true',
  'difficulty peaceful',
  'effect @s speed 30 1',
  'effect @s clear',
  'xp 10 @s',
  // Bedrock 1.26.51 command parser rejects a namespaced item id in /give and
  // has no standalone /subtitle command. Both were probe-side syntax mistakes,
  // not translation failures (BDS replied with a valid Java system_chat packet).
  // They are removed so production smoke logs contain no command syntax errors.
  'summon minecraft:xp_orb ~ ~ ~',
  'summon minecraft:villager ~2 ~ ~',
  'summon minecraft:armor_stand ~-2 ~ ~',
  'particle minecraft:flame ~ ~ ~',
  'playsound random.levelup @s',
  'title @s title {"rawtext":[{"text":"audit-title"}]}',
  'me audit-me-ok',
  'tp @s ~ ~ ~',
  'setblock ~ ~-1 ~ stone',
  'fill ~-1 ~-1 ~-1 ~ ~-1 ~ grass_block',
  'enchant @s sharpness 1',
  'recipe give @s *',
  'gamemode creative @s',
  'spawnpoint @s ~ ~ ~',
  'scriptevent mpmc:audit test',
  'setmaxplayers 10',
  'clear @s',
  'list'
]

const t0 = Date.now()
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, detail = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...detail }))

const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const clientboundSeen = new Map()
const serverboundWritten = new Map()
const serverMessages = []
const errors = []

// A single malformed high-level action must not abort the whole audit run.
process.on('uncaughtException', e => { errors.push('uncaught:' + String(e && e.message)) })
process.on('unhandledRejection', e => { errors.push('rejection:' + String(e && e.message)) })

let pump = null

bot.on('error', e => { errors.push('error:' + String(e && e.message)) })
bot.on('kicked', r => { errors.push('kicked:' + JSON.stringify(r)); log('kicked', { reason: JSON.stringify(r) }) })
bot._client.on('kick_disconnect', p => log('raw_kick_disconnect', { reason: JSON.stringify(p && p.reason), state: bot._client.state }))
bot._client.on('disconnect', p => log('raw_disconnect', { reason: JSON.stringify(p && p.reason), state: bot._client.state }))
bot.on('end', r => log('end', { reason: r || 'quit' }))
bot.on('message', m => {
  const text = String(m.toString ? m.toString() : m)
  if (serverMessages.length < 120) serverMessages.push(text)
})

// Java-side clientbound packets: name + count. If the Java parser cannot read a
// packet, node-minecraft-protocol emits an error / the connection drops, which
// is captured in errors[] and shows up as an early end.
bot._client.on('packet', (data, meta, buffer) => {
  const n = (meta && meta.name) || 'unnamed'
  clientboundSeen.set(n, (clientboundSeen.get(n) || 0) + 1)
  if (n === 'kick_disconnect' || n === 'disconnect') {
    log('raw_' + n, { state: meta && meta.state, hex: buffer && buffer.toString('hex').slice(0, 160) })
  }
})

function installWriteObserver () {
  const orig = bot._client.write.bind(bot._client)
  bot._client.write = function (packetName, params) {
    try { serverboundWritten.set(packetName, (serverboundWritten.get(packetName) || 0) + 1) } catch {}
    return orig(packetName, params)
  }
}

async function waitSane (ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (bot.entity && bot.entity.position.y > -128 && bot.entity.position.y < 512) return true
    await sleep(200)
  }
  return false
}

const attempt = async (label, fn) => {
  try { await fn(); log('action_ok', { label }) }
  catch (e) { log('action_fail', { label, message: String(e && e.message) }) }
}

bot.on('spawn', async () => {
  if (!(await waitSane(15000))) { log('desync', {}); process.exit(2) }
  pump = installInputPump(bot)
  installWriteObserver()
  pump.start()
  log('spawn', { version, pos: pos(), dimension: bot.game.dimension, gamemode: bot.game.gameMode })

  // BDS only registers the player into the command context after
  // "Player Spawned"; sending a command immediately after mineflayer's spawn
  // fails with "No targets matched selector" even for @s.
  await sleep(preDelayMs)

  if (doActions) {
    await attempt('chat', async () => bot.chat('audit-chat-ok'))
    await attempt('hotbar_0', async () => bot.setQuickBarSlot(0))
    await attempt('hotbar_1', async () => bot.setQuickBarSlot(1))
    await attempt('creative_slot', async () => {
      const Item = require('prismarine-item')(bot.version)
      bot.creative.setInventorySlot(36, new Item(bot.registry.itemsByName.stone.id, 16))
      await sleep(250)
    })
    await attempt('look', async () => { bot.look(0.4, 0.1, true); await sleep(150) })
    await attempt('swing', async () => { bot.swingArm('right'); await sleep(100) })
    await attempt('place', async () => {
      const ref = bot.blockAt(bot.entity.position.offset(1, -1, 0))
      if (ref) await bot.placeBlock(ref, new Vec3(0, 1, 0))
      await sleep(600)
    })
    await attempt('dig', async () => {
      const b = bot.blockAt(bot.entity.position.offset(1, 0, 0))
      if (b) await bot.dig(b)
      await sleep(600)
    })
    await attempt('move_forward', async () => {
      pump.control('forward', true); await sleep(500); pump.control('forward', false)
    })
    await attempt('jump', async () => { await pump.pulse('jump', 250) })
    await attempt('sneak', async () => { await pump.pulse('sneak', 250) })
    await attempt('sprint', async () => { await pump.pulse('sprint', 300) })
    await attempt('toss', async () => {
      try { await bot.toss('stone', null, 1) } catch (e) { /* creative world may refuse */ }
      await sleep(200)
    })
    await attempt('tab_complete', async () => {
      bot._client.write('tab_complete', { transactionId: 0, text: 'lis' })
      await sleep(200)
    })
    await attempt('ping', async () => {
      bot._client.write('ping_request', { id: Date.now() & 0xffffffff })
      await sleep(200)
    })
    await attempt('client_command_stats', async () => {
      bot._client.write('client_command', { actionId: 'request_stats' })
      await sleep(200)
    })
    await attempt('close_container', async () => {
      bot._client.write('close_window', { windowId: 0 })
      await sleep(150)
    })
  }

  for (const cmd of COMMANDS) {
    log('command', { cmd })
    try { bot._client.write('chat_command', { command: cmd }) }
    catch (e) { log('command_error', { cmd, message: String(e && e.message) }) }
    await sleep(gapMs)
  }

  await sleep(holdMs)

  log('final', {
    stillConnected: !bot._client.ended,
    errors,
    clientboundDistinct: clientboundSeen.size,
    clientboundNames: [...clientboundSeen.keys()].sort(),
    serverboundDistinct: serverboundWritten.size,
    serverboundNames: [...serverboundWritten.keys()].sort(),
    serverMessageCount: serverMessages.length,
    serverMessageSample: serverMessages.slice(0, 25),
    pos: pos(),
    dimension: bot.game.dimension
  })
  if (pump) pump.stop()
  try { bot.quit() } catch {}
  setTimeout(() => process.exit(errors.length ? 3 : 0), 300)
})

function pos () {
  if (!bot.entity) return null
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
}

setTimeout(() => {
  log('timeout', { clientboundDistinct: clientboundSeen.size, serverboundDistinct: serverboundWritten.size, errors })
  process.exit(errors.length ? 3 : 4)
}, Number(process.env.WP_TIMEOUT_MS || 150000))
