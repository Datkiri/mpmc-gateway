package org.mpmc.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mpmc.cli.ControlServer
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Chạy hai tiến trình CLI thật (host + join) và kiểm tra echo 1 MiB qua tunnel.
 * Không dùng Coordinator: cả hai bên bật --insecure-dev-ticket và trao đổi endpoint trực tiếp.
 */
class CliProcessIT {

    @Test
    @Timeout(180, unit = TimeUnit.SECONDS)
    fun `two cli processes tunnel one mebibyte`() {
        val hostDir = newDataDir("cli-host-")
        val joinDir = newDataDir("cli-join-")
        val hostPort = freePort()
        val hostControl = freePort()
        val joinControl = freePort()
        EchoServer(halfCloseTail = false).use { echo ->
            val host = startCli(
                "host",
                "--data-dir", hostDir.toString(),
                "--target", echo.address,
                "--bind", "127.0.0.1:$hostPort",
                "--relay-mode", "disabled",
                "--insecure-dev-ticket",
                "--control-port", hostControl.toString(),
            )
            val endpoint = host.waitFor(60_000, Regex("Endpoint host: ([0-9a-f]{64})"))
                ?: error("host không in endpoint id; log=${host.output()}")
            val join = startCli(
                "join",
                "--data-dir", joinDir.toString(),
                "--code", "DEV2345",
                "--insecure-dev-ticket",
                "--relay-mode", "disabled",
                "--dev-host-endpoint", endpoint.substringAfter("Endpoint host: ").trim(),
                "--direct", "127.0.0.1:$hostPort",
                "--listen", "127.0.0.1:0",
                "--control-port", joinControl.toString(),
            )
            try {
                val ready = join.waitFor(60_000, Regex("Sẵn sàng: 127\\.0\\.0\\.1:(\\d+)"))
                    ?: error("join không sẵn sàng; log=${join.output()}")
                val proxyPort = ready.substringAfterLast(':').trim().toInt()
                val connected = waitUntil(60_000) {
                    org.mpmc.agent.SessionStore(joinDir).read()?.state == org.mpmc.agent.SessionState.STATE_CONNECTED
                }
                assertTrue(connected, "join chưa CONNECTED; log=${join.output()}")

                Socket().use { socket ->
                    try {
                        socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 10_000)
                        socket.soTimeout = 60_000
                        socket.tcpNoDelay = true
                        val payload = ByteArray(1024 * 1024) { (it * 13).toByte() }
                        val writer = Thread {
                            val out = socket.getOutputStream()
                            var offset = 0
                            while (offset < payload.size) {
                                val chunk = minOf(16 * 1024, payload.size - offset)
                                out.write(payload, offset, chunk)
                                offset += chunk
                            }
                            out.flush()
                        }
                        writer.start()
                        val received = socket.readFully(payload.size)
                        writer.join(60_000)
                        assertTrue(payload.contentEquals(received), "dữ liệu echo không khớp")
                    } catch (e: Exception) {
                        throw AssertionError("echo lỗi; host=${host.output()}\n--- join ---\n${join.output()}", e)
                    }
                }

                assertEquals("OK stopping", ControlServer.request("stop", hostControl))
                assertEquals("OK stopping", ControlServer.request("stop", joinControl))
                assertTrue(host.process.waitFor(20, TimeUnit.SECONDS), "host không thoát")
                assertTrue(join.process.waitFor(20, TimeUnit.SECONDS), "join không thoát")
                assertEquals(0, host.process.exitValue(), "host exit=${host.output()}")
                assertEquals(0, join.process.exitValue(), "join exit=${join.output()}")
            } finally {
                host.kill()
                join.kill()
            }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `status command reads session file`() {
        val dir = newDataDir("cli-status-")
        val result = runCli("status", "--data-dir", dir.toString())
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("Không có phiên nào"), result.output)
    }

    @Test
    @Timeout(90, unit = TimeUnit.SECONDS)
    fun `doctor validates environment`() {
        val dir = newDataDir("cli-doctor-")
        val result = runCli("doctor", "--data-dir", dir.toString())
        assertTrue(result.output.contains("PASS  java"), result.output)
        assertTrue(result.output.contains("PASS  iroh-bind"), result.output)
    }

    private class Cli(
        val process: Process,
        private val stdout: StringBuilder,
    ) {
        fun output(): String = synchronized(stdout) { stdout.toString() }

        fun waitFor(timeoutMs: Long, pattern: Regex): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val match = pattern.find(output())
                if (match != null) return match.value
                if (!process.isAlive) return pattern.find(output())?.value
                Thread.sleep(100)
            }
            return pattern.find(output())?.value
        }

        fun kill() {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private class CliResult(val exitCode: Int, val output: String)

    private fun startCli(vararg args: String): Cli {
        val process = ProcessBuilder(command(args)).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) { output.append(line).append('\n') }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        return Cli(process, output)
    }

    private fun runCli(vararg args: String): CliResult {
        val process = ProcessBuilder(command(args)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return CliResult(process.exitValue(), output)
    }

    private fun command(args: Array<out String>): List<String> {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        return listOf(
            javaBin.toString(),
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            System.getProperty("java.class.path"),
            "org.mpmc.cli.MainKt",
        ) + args.toList()
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
