// Java 1.21.2+ clients send `tick_end` every tick. ViaBedrock only emits the
// Bedrock PlayerAuthInput packet from that handler, so a client that never
// sends it (mineflayer does not) produces zero movement, jump, sprint and
// block-break actions server-side, even though local physics looks correct.

const TICK_MS = 50
const MOVEMENT_PACKETS = new Set(['position', 'position_look', 'look', 'flying'])

function installInputPump(bot, opts = {}) {
  const state = {
    forward: false,
    backward: false,
    left: false,
    right: false,
    jump: false,
    shift: false,
    sprint: false
  }
  let timer = null
  let running = false
  let lastTickAt = 0

  const originalWrite = bot._client.write.bind(bot._client)

  const writeInput = () => {
    if (!running) return
    try {
      originalWrite('player_input', { inputs: { ...state } })
    } catch {}
  }

  // A vanilla client sends its movement packet and then tick_end in the same
  // tick. ViaBedrock snapshots the position when it sees tick_end, so emitting
  // tick_end before the position update makes the Bedrock host predict a stale
  // position and rewind the player.
  const emitTick = () => {
    if (!running) return
    lastTickAt = Date.now()
    writeInput()
    try {
      originalWrite('tick_end', {})
    } catch {}
  }

  // mineflayer still writes legacy entity_action ids for sprint/sneak; Java
  // 26.3 reads those ordinals as START_RIDING_JUMP and ViaBedrock throws.
  // Sprint and sneak travel in player_input instead.
  bot._client.write = function (name, params) {
    if (name === 'entity_action' && params) {
      const action = params.actionId
      if (action === 'start_sprinting' || action === 'stop_sprinting' ||
          action === 'start_sneaking' || action === 'stop_sneaking' ||
          action >= 0 && action <= 4) {
        return
      }
    }
    const result = originalWrite(name, params)
    if (MOVEMENT_PACKETS.has(name) && Date.now() - lastTickAt >= 35) emitTick()
    return result
  }

  const send = () => writeInput()

  const set = (flag, value) => {
    if (!(flag in state)) throw new Error(`unknown input flag ${flag}`)
    if (state[flag] === value) return
    state[flag] = value
    send()
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
    bot.setControlState(c.mineflayer, value)
    set(c.input, value)
  }

  const pulse = async (flag, ms) => {
    control(flag, true)
    await new Promise(r => setTimeout(r, ms))
    control(flag, false)
  }

  const tick = () => {
    if (!running) return
    if (Date.now() - lastTickAt >= 45) emitTick()
  }

  const start = () => {
    if (running) return
    running = true
    emitTick()
    timer = setInterval(tick, opts.intervalMs || TICK_MS)
  }

  const stop = () => {
    running = false
    if (timer) clearInterval(timer)
    timer = null
  }

  bot.on('end', stop)

  return { start, stop, set, control, pulse, state }
}

module.exports = { installInputPump }
