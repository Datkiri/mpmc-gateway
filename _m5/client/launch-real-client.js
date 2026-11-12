const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')

const MC_DIR = process.env.MC_DIR || path.join(process.env.APPDATA, '.minecraft')
const VERSION = process.env.MC_VERSION || '1.21.1'
const USERNAME = process.env.MC_USER || 'JavaReal'
const SERVER = process.env.MC_SERVER || '127.0.0.1:25569'
const MEMORY = process.env.MC_MEMORY || '2G'
const JAVA = process.env.JAVA_BIN || 'java'

const versionDir = path.join(MC_DIR, 'versions', VERSION)
const versionJson = JSON.parse(fs.readFileSync(path.join(versionDir, `${VERSION}.json`), 'utf8'))

function archName() {
  return process.arch === 'x64' ? 'x86_64' : process.arch
}

function libraryAllowed(lib) {
  if (!lib.rules || lib.rules.length === 0) return true
  let allowed = false
  for (const rule of lib.rules) {
    const os = rule.os || {}
    const osMatches = !os.name || os.name === 'windows'
    const archMatches = !os.arch || os.arch === archName()
    const matches = osMatches && archMatches
    if (rule.action === 'allow' && matches) allowed = true
    if (rule.action === 'disallow' && matches) return false
  }
  return allowed
}

const classpath = []
for (const lib of versionJson.libraries) {
  if (!libraryAllowed(lib)) continue
  const artifact = lib.downloads && lib.downloads.artifact
  if (!artifact || !artifact.path) continue
  const jar = path.join(MC_DIR, 'libraries', artifact.path)
  if (!fs.existsSync(jar)) {
    console.warn('missing library:', jar)
    continue
  }
  classpath.push(jar)
}
classpath.push(path.join(versionDir, `${VERSION}.jar`))

const gameDir = process.env.MC_GAMEDIR || path.join(MC_DIR, 'real-test')
fs.mkdirSync(gameDir, { recursive: true })

const args = [
  `-Xmx${MEMORY}`,
  '-Dfile.encoding=UTF-8',
  '-cp', classpath.join(';'),
  'net.minecraft.client.main.Main',
  '--username', USERNAME,
  '--version', VERSION,
  '--gameDir', gameDir,
  '--assetsDir', path.join(MC_DIR, 'assets'),
  '--assetIndex', versionJson.assetIndex.id,
  '--uuid', '00000000000000000000000000000001',
  '--accessToken', '0',
  '--userType', 'legacy',
  '--versionType', 'release',
  '--width', '1280',
  '--height', '720',
  '--quickPlayMultiplayer', SERVER
]

const logPath = path.join(gameDir, 'launch.out.log')
const out = fs.openSync(logPath, 'a')
console.log('version:', VERSION, 'user:', USERNAME, 'server:', SERVER)
console.log('classpath entries:', classpath.length - 1)
console.log('gameDir:', gameDir)
console.log('log:', logPath)

const child = spawn(JAVA, args, { detached: true, stdio: ['ignore', out, out], windowsHide: false })
child.unref()
console.log('pid:', child.pid)
