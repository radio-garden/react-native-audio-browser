package com.audiobrowser.sonos

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import timber.log.Timber

/** A Wi-Fi multicast lock, held while discovery listens for replies. */
internal interface MulticastLockHandle {
  fun acquire()

  fun release()
}

/**
 * Sends the SSDP `M-SEARCH` to the UPnP multicast group and collects the replies for a bounded
 * window, holding the multicast lock meanwhile. Blocking: run it off the main thread.
 */
internal class SsdpDiscovery(private val multicastLock: MulticastLockHandle? = null) : SsdpScanner {

  /**
   * Sends `repeats` probes (at least one), then listens for `timeoutMs`. A failed probe is ignored;
   * any other socket failure is logged, not thrown, and the replies so far are returned, one per
   * USN, the first one kept.
   */
  override fun search(timeoutMs: Int, repeats: Int): List<SsdpResponse> {
    multicastLock?.acquire()
    val responsesByUsn = LinkedHashMap<String, SsdpResponse>()
    try {
      DatagramSocket().use { socket ->
        val group = InetAddress.getByName(SsdpMessages.MULTICAST_HOST)
        val datagram = SsdpMessages.buildMSearch()
        // UDP is lossy: one probe is easily missed on busy Wi-Fi.
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
