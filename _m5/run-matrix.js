const { spawn } = require('child_process')
const path = require('path')

const clientDir = path.join(__dirname, 'client')
const botScript = path.join(clientDir, 'bot.js')

function runBot({ port, user, holdMs = 3000, version = '1.21.11' }) {
  return new Promise(resolve => {
    const child = spawn(process.execPath, [botScript], {
      cwd: clientDir,
      env: { ...process.env, MC_PORT: String(port), MC_USER: user, HOLD_MS: String(holdMs), MC_VERSION: version },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let out = ''
    let err = ''
    child.stdout.on('data', d => { out += d })
    child.stderr.on('data', d => { err += d })
    const timer = setTimeout(() => child.kill(), 90000)
    child.on('exit', code => {
      clearTimeout(timer)
      const lines = out.trim().split(/\r?\n/).filter(Boolean)
      const json = [...lines].reverse().map(l => { try { return JSON.parse(l) } catch { return null } }).find(Boolean)
      resolve({ code, json, stderr: err.slice(-500) })
    })
  })
}

function stats(xs) {
  const sorted = [...xs].sort((a, b) => a - b)
  const avg = xs.reduce((a, b) => a + b, 0) / xs.length
  return {
    n: xs.length,
    min: Math.round(sorted[0]),
    median: Math.round(sorted[Math.floor(sorted.length / 2)]),
    max: Math.round(sorted[sorted.length - 1]),
    avg: Math.round(avg),
  }
}

async function baseline(port, label, runs) {
  const results = []
  for (let i = 0; i < runs; i++) {
    const r = await runBot({ port, user: `${label}_${i}` })
    results.push(r)
  }
  const ok = results.filter(r => r.json?.ok)
  const login = stats(ok.map(r => r.json.loginMs))
  const spawn = stats(ok.map(r => r.json.spawnMs))
  console.log(JSON.stringify({ label, runs, ok: ok.length, loginMs: login, spawnMs: spawn, failures: results.filter(r => !r.json?.ok).map(r => r.json?.reason || r.stderr) }))
  return ok.length
}

async function concurrent(port, n, holdMs) {
  const started = Date.now()
  const jobs = []
  for (let i = 0; i < n; i++) {
    jobs.push(runBot({ port, user: `conc_${n}_${i}`, holdMs }))
    await new Promise(r => setTimeout(r, 150))
  }
  const results = await Promise.all(jobs)
  const ok = results.filter(r => r.json?.ok)
  const wallMs = Date.now() - started
  console.log(JSON.stringify({
    label: `concurrent_${n}`,
    n,
    ok: ok.length,
    wallMs,
    login: ok.length ? stats(ok.map(r => r.json.loginMs)) : null,
    spawn: ok.length ? stats(ok.map(r => r.json.spawnMs)) : null,
    failures: results.filter(r => !r.json?.ok).map(r => r.json?.reason || r.stderr),
  }))
}

;(async () => {
  const mode = process.argv[2] || 'all'
  if (mode === 'all' || mode === 'baseline') {
    await baseline(25565, 'direct', 3)
    await baseline(25566, 'tunnel', 3)
  }
  if (mode === 'all' || mode === 'concurrent') {
    for (const n of [1, 5, 10]) {
      await concurrent(25566, n, 5000)
    }
  }
})()
