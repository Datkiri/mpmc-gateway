const net = require('net')

const port = Number(process.env.PORT || 25570)
let connections = 0
let bytes = 0

const server = net.createServer(socket => {
  connections += 1
  socket.on('data', d => { bytes += d.length })
  socket.on('error', () => {})
  socket.pipe(socket)
})

server.listen(port, '127.0.0.1', () => {
  console.log(`echo listening ${port}`)
})

setInterval(() => {
  if (bytes > 0) console.log(`connections=${connections} bytes=${bytes}`)
}, 5000)

process.on('SIGTERM', () => server.close(() => process.exit(0)))
process.on('SIGINT', () => server.close(() => process.exit(0)))
