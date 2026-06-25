package com.audiobrowser.destination.sonos

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import timber.log.Timber

/**
 * Acquire/release wrapper around a Wi-Fi multicast lock (`WifiManager.MulticastLock`), so the socket
 * I/O here stays decoupled from Android. The provider supplies one built from a `Context`.
 */
interface MulticastLockHandle {
  fun acquire()

  fun release()
}

/**
 * The real [SsdpScanner]: sends the SSDP `M-SEARCH` datagram to the UPnP multicast group and collects
 * the unicast replies for a bounded window. Blocking — run off the main thread.
 *
 * Not unit-tested (it does real UDP I/O); the message build/parse it relies on is covered by
 * [SsdpMessagesTest], and the end-to-end path is on the manual hardware checklist. A
 * [MulticastLockHandle] is held for the duration so Wi-Fi power-saving does not drop replies.
 */
class SsdpDiscovery(
  private val multicastLock: MulticastLockHandle? = null,
) : SsdpScanner {

  override fun search(timeoutMs: Int, repeats: Int): List<SsdpResponse> {
    multicastLock?.acquire()
    val responsesByUsn = LinkedHashMap<String, SsdpResponse>()
    try {
      DatagramSocket().use { socket ->
        socket.reuseAddress = true
        val group = InetAddress.getByName(SsdpMessages.MULTICAST_HOST)
        val datagram = SsdpMessages.buildMSearch()
        // Send a few times — UDP is lossy and a single probe is easily missed on busy Wi-Fi.
        repeat(repeats.coerceAtLeast(1)) {
          runCatching {
            socket.send(DatagramPacket(datagram, datagram.size, group, SsdpMessages.MULTICAST_PORT))
          }
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (true) {
          val remaining = deadline - System.currentTimeMillis()
          if (remaining <= 0) break
          socket.soTimeout = remaining.toInt().coerceAtLeast(1)
          try {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
            SsdpMessages.parseResponse(raw)?.let { responsesByUsn.putIfAbsent(it.usn, it) }
          } catch (_: SocketTimeoutException) {
            break
          }
        }
      }
    } catch (t: Throwable) {
      Timber.w(t, "SSDP discovery failed")
    } finally {
      multicastLock?.release()
    }
    return responsesByUsn.values.toList()
  }

  private companion object {
    const val RECEIVE_BUFFER_BYTES = 2048
  }
}
