package app.opendisplay.android.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.opendisplay.android.protocol.WireProtocol

/**
 * Bonjour advertisement (PROTOCOL.md 2.1): `_opensidecar._tcp` with TXT `id`
 * (must equal hello.id) and `pv`. The name is display-only.
 */
class Advertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    var registeredName: String? = null
        private set

    fun register(name: String, installId: String, port: Int = WireProtocol.TCP_PORT) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = WireProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("id", installId)
            setAttribute("pv", WireProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // NSD may rename on a collision ("Pixel (2)").
                registeredName = info.serviceName
                Log.i(TAG, "advertising '${info.serviceName}' as ${WireProtocol.SERVICE_TYPE}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        val listener = registration ?: return
        registration = null
        registeredName = null
        try {
            nsd.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "NSD unregister: $e")
        }
    }

    private companion object {
        const val TAG = "OpenDisplay"
    }
}
