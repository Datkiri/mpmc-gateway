const mineflayer = require('mineflayer')

const host = process.env.MC_HOST || '127.0.0.1'
const port = Number(process.env.MC_PORT || 25566)
const version = process.env.MC_VERSION || '1.21.11'
const name = process.env.MC_USER || 'ProbeBot'
const seconds = Number(process.env.PROBE_SECONDS || 20)

const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })

let columns = 0
let loaded = 0
bot.on('chunkColumnLoad', () => { loaded++ })

function pos() {
  if (!bot.entity) return null
  const p = bot.entity.position
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) }
}

bot.on('spawn', () => {
  console.log(JSON.stringify({ ev: 'spawn', at: Date.now() - t0, pos: pos() }))
})

bot.on('game', () => {
  const g = bot.game
  console.log(JSON.stringify({
    ev: 'game', at: Date.now() - t0,
    dimension: g.dimension, worldName: g.worldName, gameMode: g.gameMode,
    minY: g.minY, height: g.worldHeight,
  }))
})

bot.on('forcedMove', () => {
  console.log(JSON.stringify({ ev: 'forcedMove', at: Date.now() - t0, pos: pos() }))
})

bot.on('kicked', reason => {
  console.log(JSON.stringify({ ev: 'kicked', at: Date.now() - t0, reason }))
})

bot.on('end', reason => {
  console.log(JSON.stringify({ ev: 'end', at: Date.now() - t0, reason }))
  process.exit(0)
})

bot.on('error', err => {
  console.log(JSON.stringify({ ev: 'error', at: Date.now() - t0, message: err.message }))
})

const timer = setInterval(() => {
  if (!bot.entity) return
  columns = Object.keys(bot.world?.columns || {}).length
  console.log(JSON.stringify({
    ev: 'tick', at: Date.now() - t0, pos: pos(),
    onGround: bot.entity.onGround, columns, loaded,
    blockUnder: bot.blockAt(bot.entity.position.offset(0, -1, 0))?.name ?? null,
  }))
}, 1000)

setTimeout(() => {
  clearInterval(timer)
  console.log(JSON.stringify({ ev: 'done', at: Date.now() - t0, pos: pos(), columns }))
  process.exit(0)
}, seconds * 1000)
