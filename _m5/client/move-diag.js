const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.11'
const name = process.env.MC_USER || 'MoveDiag'

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))

const outCounts = {}
let physicsTicks = 0
let posPacketsFromServer = 0

bot.on('physicsTick', () => { physicsTicks++ })
bot.on('packet', (data, meta) => {
  if (meta.state !== 'play') return
  outCounts[meta.name] = (outCounts[meta.name] || 0) + 1
})
bot._client.on('write', (packet, meta) => {
  if (meta.state !== 'play') return
  const name = meta.name || packet?.name
  outCounts['out:' + name] = (outCounts['out:' + name] || 0) + 1
})

function pos() {
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
}

let started = false
async function run() {
  if (started) return
  started = true
  await sleep(2500)
  console.log(JSON.stringify({
    ev: 'state', at: Date.now() - t0, pos: pos(), onGround: bot.entity.onGround,
    physics: bot.physics?.enabled, physicsTicks,
    game: { gameMode: bot.game.gameMode, dimension: bot.game.dimension },
    supportNewInput: bot.supportFeature('newPlayerInputPacket'),
  }))
  bot.setControlState('forward', true)
  const before = pos()
  await sleep(3000)
  bot.setControlState('forward', false)
  await sleep(500)
  console.log(JSON.stringify({
    ev: 'walked', at: Date.now() - t0, before, after: pos(),
    physicsTicks, outCounts,
  }))
  process.exit(0)
}

bot.on('spawn', run)
bot.on('kicked', r => { console.log(JSON.stringify({ ev: 'kicked', reason: r })); process.exit(1) })
bot.on('error', e => { console.log(JSON.stringify({ ev: 'error', message: e.message })); process.exit(1) })
setTimeout(() => { console.log(JSON.stringify({ ev: 'timeout', pos: bot.entity ? pos() : null, physicsTicks, outCounts })); process.exit(0) }, 40000)
