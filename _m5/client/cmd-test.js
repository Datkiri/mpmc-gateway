const mineflayer = require('mineflayer')
const host = '127.0.0.1'
const port = 25566
const version = process.env.MC_VERSION || '1.21.4'
const name = 'ActionBot'
const t0 = Date.now()
const bot = mineflayer.createBot({ host, port, version, username: name, auth: 'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (ev, d = {}) => console.log(JSON.stringify({ ms: Date.now() - t0, ev, ...d }))
bot.on('message', m => log('chat', { text: m.toString().slice(0, 160) }))
bot.on('spawn', async () => {
  await sleep(1500)
  log('test_chat', {})
  bot.chat('mpmc chat test')
  await sleep(1500)
  for (const cmd of ['/help', '/version', '/gamerule sendcommandfeedback', '/time query daytime']) {
    log('cmd', { cmd })
    bot.chat(cmd)
    await sleep(2000)
  }
  bot.quit()
  setTimeout(() => process.exit(0), 300)
})
bot.on('kicked', r => { log('kicked', { reason: String(r).slice(0, 200) }); process.exit(1) })
