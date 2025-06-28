package home.wyoming_android_tts

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class WyomingDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var currentServiceName: String? = null

    companion object {
        private const val TAG = "WyomingDiscoveryManager"
        const val SERVICE_TYPE = "_wyoming._tcp"
    }

    fun registerService(instanceName: String, port: Int) {
        if (registrationListener != null) {
            Log.d(TAG, "Service already registered or registration in progress. Unregistering first.")
            // Attempt to unregister previous service if any, to avoid conflicts
            // This is a simple approach; a more robust solution might queue requests or manage states.
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "No listener to unregister or already unregistered: ${e.message}")
            }
            registrationListener = null // Ensure we create a new one
        }
        currentServiceName = instanceName

        initializeRegistrationListener()

        val serviceInfo = NsdServiceInfo().apply {
            // The name is subject to change based on conflicts
            // however the NSD system will handle conflicts.
            // The name you register is a suggested name.
            serviceName = instanceName
            serviceType = SERVICE_TYPE
            setPort(port)
            // No TXT records needed as per discussion
        }

        Log.d(TAG, "Attempting to register service: $instanceName, type: $SERVICE_TYPE, port: $port")
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service", e)
            // Potentially clean up listener if registration call fails immediately
            registrationListener = null
            currentServiceName = null
        }
    }

    private fun initializeRegistrationListener() {
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                // Save the service name that Network Service Discovery successfuly registered.
                // This might be different from the name originally requested if there was a conflict.
                currentServiceName = NsdServiceInfo.serviceName
                Log.i(TAG, "Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Registration failed! Put debugging code here to determine why.
                Log.e(TAG, "Service registration failed. Error code: $errorCode, Service: ${serviceInfo.serviceName}")
                // Clean up
                this@WyomingDiscoveryManager.registrationListener = null
                this@WyomingDiscoveryManager.currentServiceName = null
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                // Service has been unregistered. This is invoked when unregisterService() is called.
                Log.i(TAG, "Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Unregistration failed. Put debugging code here to determine why.
                Log.e(TAG, "Service unregistration failed. Error code: $errorCode, Service: ${serviceInfo.serviceName}")
            }
        }
    }

    fun unregisterService() {
        Log.d(TAG, "Attempting to unregister service")
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
                Log.i(TAG, "Unregister service call successful for listener: $registrationListener")
            } catch (e: IllegalArgumentException) {
                // This can happen if the listener was not registered or already unregistered
                Log.w(TAG, "Error unregistering service: Listener not valid or already unregistered. ${e.message}")
            } finally {
                // Clean up references regardless of success or failure of the unregister call itself,
                // as the listener instance is now considered consumed.
                registrationListener = null
                currentServiceName = null
            }
        } else {
            Log.d(TAG, "No active registration listener to unregister.")
        }
    }
}
