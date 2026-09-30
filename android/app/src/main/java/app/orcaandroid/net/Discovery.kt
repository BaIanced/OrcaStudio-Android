package app.orcaandroid.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.thread

/** A printer found on the local network. */
data class DiscoveredPrinter(val name: String, val type: HostType, val address: String, val serial: String = "")

/**
 * Finds printers on the LAN: Moonraker, OctoPrint and PrusaLink via mDNS/DNS-SD, Bambu Lab
 * printers via their SSDP announcements (UDP 2021). Emits each printer once while collected.
 */
class Discovery(private val context: Context) {

    fun scan(): Flow<DiscoveredPrinter> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val seen = HashSet<String>()
        fun emit(p: DiscoveredPrinter) {
            if (seen.add("${p.type}:${p.address}")) trySend(p)
        }

        val listeners = SERVICES.map { (service, type) ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = info.host?.hostAddress ?: return
                            val port = if (info.port == 80) "" else ":${info.port}"
                            emit(DiscoveredPrinter(info.serviceName, type, "$host$port"))
                        }
                    })
                }
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }.also { nsd.discoverServices(service, NsdManager.PROTOCOL_DNS_SD, it) }
        }

        // Bambu printers broadcast SSDP NOTIFY messages every few seconds.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicast = wifi.createMulticastLock("orca-discovery").apply { setReferenceCounted(false); acquire() }
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val ssdp = thread(name = "bambu-ssdp", isDaemon = true) {
            runCatching {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.soTimeout = 1_000
                    socket.bind(InetSocketAddress(2021))
                    val buf = ByteArray(2048)
                    while (running.get()) {
                        val packet = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val headers = String(packet.data, 0, packet.length).lines()
                            .mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }
                            .toMap()
                        if (headers["nt"]?.contains("bambulab", ignoreCase = true) != true) continue
                        val ip = headers["location"] ?: packet.address.hostAddress ?: continue
                        emit(DiscoveredPrinter(headers["devname.bambu.com"] ?: "Bambu Lab", HostType.BAMBU, ip, headers["usn"].orEmpty()))
                    }
                }
            }
        }

        awaitClose {
            running.set(false)
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            multicast.release()
            ssdp.interrupt()
        }
    }

    private companion object {
        val SERVICES = listOf(
            "_moonraker._tcp." to HostType.MOONRAKER,
            "_octoprint._tcp." to HostType.OCTOPRINT,
            "_prusa-link._tcp." to HostType.PRUSALINK,
        )
    }
}
