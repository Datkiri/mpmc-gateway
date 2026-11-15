// Modern Java requires a position/input/tick_end sequence. Older clients rely
// on ViaBackwards to synthesize input and tick-end packets from their movement.
const TICK_MS = 50

function installInputPump (bot, opts = {}) {
  const protocol = require('minecraft-data')(bot.version).version.version
  const modern = protocol >= 768
  const state = { forward: false, backward: false, left: false, right: false, jump: false, shift: false, sprint: false }
  const originalWrite = bot._client.write.bind(bot._client)
  let timer = null
  let running = false

  const writeInput = () => {
    if (running && modern) originalWrite('player_input', { inputs: { ...state } })
  }

  const sendPosition = () => {
    if (!running || !bot.entity || bot._client.state !== 'play') return false
    const p = bot.entity.position
    if (![p.x, p.y, p.z].every(Number.isFinite)) return false
    const onGround = !!bot.entity.onGround
    originalWrite('position', { x: p.x, y: p.y, z: p.z, onGround, flags: { onGround, hasHorizontalCollision: !!bot.entity.isCollidedHorizontally } })
    return true
  }

  const emitTick = () => {
    if (!running || !modern) return
    // Snapshot the current position immediately before ending the tick. A
    // fallback tick followed by a gated movement tick can otherwise stay stale.
    if (!sendPosition()) return
    writeInput()
    originalWrite('tick_end', {})
  }

  const set = (flag, value) => {
    if (!(flag in state)) throw new Error(`unknown input flag ${flag}`)
    if (state[flag] === value) return
    state[flag] = value
    writeInput()
  }

  const controls = {
    forward: { mineflayer: 'forward', input: 'forward' },
    backward: { mineflayer: 'back', input: 'backward' },
    back: { mineflayer: 'back', input: 'backward' },
    left: { mineflayer: 'left', input: 'left' },
    right: { mineflayer: 'right', input: 'right' },
    jump: { mineflayer: 'jump', input: 'jump' },
    sneak: { mineflayer: 'sneak', input: 'shift' },
    sprint: { mineflayer: 'sprint', input: 'sprint' }
  }

  const control = (flag, value) => {
    const c = controls[flag]
    if (!c) throw new Error(`unknown control ${flag}`)
    // Keep mineflayer's version-specific entity_action sprint/sneak packets.
    bot.setControlState(c.mineflayer, value)
    set(c.input, value)
  }

  const pulse = async (flag, ms) => {
    control(flag, true)
    await new Promise(resolve => setTimeout(resolve, ms))
    control(flag, false)
  }

  const start = () => {
    if (running) return
    running = true
    if (modern) {
      emitTick()
      timer = setInterval(emitTick, opts.intervalMs || TICK_MS)
    }
  }

  const stop = () => {
    running = false
    if (timer) clearInterval(timer)
    timer = null
  }

  bot.on('end', stop)
  return { start, stop, set, control, pulse, state, modern, sendPosition }
}

module.exports = { installInputPump }
