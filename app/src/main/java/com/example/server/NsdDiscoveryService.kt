package com.example.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.domain.model.DiscoveredLanServer
import com.example.security.SecureVaultLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Automatic LAN discovery using Android's native mDNS / Network Service Discovery (NsdManager)
 * under service type `_homevault._tcp.`.
 */
class NsdDiscoveryService(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val serviceType = "_homevault._tcp."

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredServers = MutableStateFlow<List<DiscoveredLanServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredLanServer>> = _discoveredServers.asStateFlow()

    fun registerServer(
        deviceId: String,
        deviceName: String,
        port: Int,
        tlsFingerprint: String
    ) {
        unregisterServer()
        val manager = nsdManager ?: return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "HomeVault-$deviceName".take(40)
            serviceType = this@NsdDiscoveryService.serviceType
            setPort(port)
            setAttribute("deviceId", deviceId)
            setAttribute("deviceName", deviceName)
            setAttribute("fp", tlsFingerprint.take(64))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                SecureVaultLogger.d("NSD", "Registered LAN service: ${info.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                SecureVaultLogger.w("NSD", "Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun unregisterServer() {
        val listener = registrationListener ?: return
        runCatching { nsdManager?.unregisterService(listener) }
        registrationListener = null
    }

    fun startDiscovery() {
        stopDiscovery()
        val manager = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_homevault")) return
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = resolved.host?.hostAddress ?: return
                            val attrs = resolved.attributes
                            val devId = attrs["deviceId"]?.let { String(it) } ?: resolved.serviceName
                            val devName = attrs["deviceName"]?.let { String(it) } ?: resolved.serviceName
                            val fp = attrs["fp"]?.let { String(it) } ?: ""
                            val entry = DiscoveredLanServer(
                                deviceId = devId,
                                deviceName = devName,
                                host = host,
                                port = resolved.port,
                                tlsFingerprint = fp
                            )
                            val updated = _discoveredServers.value
                                .filterNot { it.deviceId == entry.deviceId } + entry
                            _discoveredServers.value = updated
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val devName = service.serviceName
                _discoveredServers.value = _discoveredServers.value.filterNot {
                    devName.contains(it.deviceName)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        runCatching { nsdManager?.stopServiceDiscovery(listener) }
        discoveryListener = null
    }
}
