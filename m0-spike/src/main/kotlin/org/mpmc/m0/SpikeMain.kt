package org.mpmc.m0

import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointId
import computer.iroh.IrohException
import computer.iroh.PathSnapshot
import computer.iroh.RecvStream
import computer.iroh.RelayMode
import computer.iroh.SendStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private const val MIB = 1024 * 1024
private const val CHUNK = 64 * 1024
private const val ALPN = "mpmc-m0/1"

private const val USAGE = """
mpmc M0 spike - kiem thu iroh JVM binding (computer.iroh:iroh:1.2.0)

  listen [--relay n0|disabled|custom:<url>] [--bind <ip:port>]
      Bind endpoint, in endpoint id, nhan 1 ket noi, doc 1 MiB roi echo lai.

  dial <endpoint-id> [--relay n0|disabled|custom:<url>] [--direct <ip:port>] [--relay-url <url>] [--timeout <s>]
      Connect bang id-only, gui 1 MiB, doc echo, verify tung byte.
      --relay-url: (spike only) truyen relay URL cua host vao EndpointAddr de test duong relay.

  help
"""

fun main(args: Array<String>) {
    try {
        when {
            args.isEmpty() || args[0] == "help" -> println(USAGE)
            args[0] == "listen" -> runBlocking { listen(args.drop(1)) }
            args[0] == "dial" -> runBlocking { dial(args.drop(1)) }
            else -> {
                System.err.println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: IrohException) {
        System.err.println("iroh_error=${e.message()}")
        exitProcess(1)
    } catch (e: Exception) {
        e.printStackTrace()
        exitProcess(1)
    }
}

private sealed interface RelayArg {
    data object N0 : RelayArg
    data object Disabled : RelayArg
    data class Custom(val urls: List<String>) : RelayArg
}

private fun parseRelay(args: List<String>): RelayArg {
    val i = args.indexOf("--relay")
    if (i < 0 || i + 1 >= args.size) return RelayArg.N0
    return when (val v = args[i + 1]) {
        "n0" -> RelayArg.N0
        "disabled" -> RelayArg.Disabled
        else -> {
            require(v.startsWith("custom:")) { "gia tri --relay khong hop le: $v" }
            RelayArg.Custom(v.removePrefix("custom:").split(',').filter { it.isNotBlank() })
        }
    }
}

private fun argAfter(args: List<String>, flag: String): String? {
    val i = args.indexOf(flag)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private suspend fun bindEndpoint(relay: RelayArg, bindAddr: String?): Pair<Endpoint, Long> {
    val builder = EndpointBuilder()
    if (bindAddr != null) builder.bindAddr(bindAddr)
    when (relay) {
        RelayArg.N0 -> builder.applyN0()
        RelayArg.Disabled -> {
            // preset bat buoc de cai rustls crypto provider; khong co se loi luc bind
            builder.applyMinimal()
            builder.relayMode(RelayMode.disabled())
        }
        is RelayArg.Custom -> {
            builder.applyMinimal()
            builder.relayMode(RelayMode.customFromUrls(relay.urls))
        }
    }
    builder.alpns(listOf(ALPN.encodeToByteArray()))
    var endpoint: Endpoint? = null
    val ms = measureTimeMillis { endpoint = builder.bind() }
    return endpoint!! to ms
}

private fun fmtPaths(paths: List<PathSnapshot>): String =
    if (paths.isEmpty()) "[]"
    else paths.joinToString(prefix = "[", postfix = "]") { p ->
        "id=${p.id},sel=${p.isSelected},ip=${p.isIp},relay=${p.isRelay},rttMs=${p.rttMs},addr=${p.remoteAddr}"
    }

private fun payload(): ByteArray {
    val data = ByteArray(MIB)
    var x = 0x9E3779B9.toInt()
    for (i in data.indices) {
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        data[i] = x.toByte()
    }
    return data
}

private fun memoryStats(): String {
    val rt = Runtime.getRuntime()
    val heapMb = (rt.totalMemory() - rt.freeMemory()) / MIB
    val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
    val committedMb =
        if (os is com.sun.management.OperatingSystemMXBean) os.committedVirtualMemorySize / MIB else -1L
    return "heap_mb=$heapMb committed_mb=$committedMb"
}

private suspend fun echo(recv: RecvStream, send: SendStream): Triple<Long, Long, Double> {
    var total = 0L
    val t0 = System.nanoTime()
    while (true) {
        val chunk = recv.read(CHUNK.toUInt()) ?: break
        if (chunk.isEmpty()) break
        total += chunk.size
        send.writeAll(chunk)
    }
    send.finish()
    val ms = (System.nanoTime() - t0) / 1_000_000
    val mbps = if (ms > 0) total.toDouble() / MIB * 1000.0 / ms else 0.0
    return Triple(total, ms, mbps)
}

private suspend fun listen(args: List<String>) {
    val relay = parseRelay(args)
    val bindAddr = argAfter(args, "--bind")
    val (endpoint, bindMs) = bindEndpoint(relay, bindAddr)
    try {
        println("role=listen")
        println("endpoint_id=${endpoint.id()}")
        println("bind_ms=$bindMs")
        println("bound_sockets=${endpoint.boundSockets()}")
        val incoming = withTimeout(180_000) { endpoint.acceptNext() }
            ?: run {
                println("accept_next=null")
                return
            }
        println("incoming=accepted")
        val conn = withTimeout(30_000) { incoming.accept().connect() }
        println("remote_id=${conn.remoteId()}")
        println("paths_before=${fmtPaths(conn.paths())}")
        val bi = conn.acceptBi()
        val send = bi.send()
        val recv = bi.recv()
        val (n, ms, mbps) = echo(recv, send)
        println("echo_bytes=$n")
        println("echo_ms=$ms")
        println("echo_mbps=${"%.2f".format(mbps)}")
        println("paths_after=${fmtPaths(conn.paths())}")
        println(memoryStats())
        conn.close(0L, byteArrayOf())
        println("closed=ok")
    } finally {
        endpoint.shutdown()
        println("shutdown=ok")
    }
}

private suspend fun dial(args: List<String>) {
    require(args.isNotEmpty()) { "dial can endpoint id" }
    val remoteIdStr = args[0]
    val rest = args.drop(1)
    val relay = parseRelay(rest)
    val direct = argAfter(rest, "--direct")
    val relayUrl = argAfter(rest, "--relay-url")
    val timeoutS = argAfter(rest, "--timeout")?.toLongOrNull() ?: 30L
    val (endpoint, bindMs) = bindEndpoint(relay, null)
    try {
        println("role=dial")
        println("endpoint_id=${endpoint.id()}")
        println("bind_ms=$bindMs")
        val remoteId = EndpointId.fromString(remoteIdStr)
        val addr = EndpointAddr(
            remoteId,
            relayUrl,
            if (direct != null) listOf(direct) else emptyList(),
        )
        val conn = withTimeout(timeoutS * 1000) { endpoint.connect(addr, ALPN.encodeToByteArray()) }
        println("connect=ok")
        println("remote_id=${conn.remoteId()}")
        println("paths_before=${fmtPaths(conn.paths())}")
        val bi = conn.openBi()
        val send = bi.send()
        val recv = bi.recv()
        val data = payload()
        val sendMs = measureTimeMillis {
            send.writeAll(data)
            send.finish()
        }
        var total = 0L
        var ok = true
        loop@ while (true) {
            val chunk = recv.read(CHUNK.toUInt()) ?: break
            if (chunk.isEmpty()) break
            if (total + chunk.size > MIB) {
                ok = false
                break@loop
            }
            for (i in chunk.indices) {
                if (chunk[i] != data[total.toInt() + i]) {
                    ok = false
                    break@loop
                }
            }
            total += chunk.size
        }
        val verified = ok && total == MIB.toLong()
        println("send_mib_ms=$sendMs")
        println("echo_bytes=$total")
        println("echo_ok=$verified")
        println("paths_after=${fmtPaths(conn.paths())}")
        println(memoryStats())
        conn.close(0L, byteArrayOf())
        println("closed=ok")
        if (!verified) {
            System.err.println("VERIFY_FAILED echo=$total/$MIB")
            exitProcess(3)
        }
    } finally {
        endpoint.shutdown()
        println("shutdown=ok")
    }
}
