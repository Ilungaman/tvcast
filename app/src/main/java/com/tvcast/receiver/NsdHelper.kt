package com.tvcast.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Регистрация сервиса в локальной сети через mDNS/Bonjour (_displaystream._tcp).
 * Нужна, чтобы нативные приложения-отправители могли найти телевизор без ввода IP.
 * Веб-интерфейс в Safari работает и без этого — по адресу с экрана.
 */
class NsdHelper(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun register(port: Int, deviceName: String) {
        unregister()
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("tvcast-mdns")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val info = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = SERVICE_TYPE
                setPort(port)
            }

            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD зарегистрирован: ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD не зарегистрирован, код $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD снят с регистрации")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD: ошибка снятия регистрации, код $errorCode")
                }
            }
            listener = l
            nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager).also {
                it.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NSD недоступен: ${t.message}")
        }
    }

    fun unregister() {
        try {
            listener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Throwable) {
        }
        listener = null
        nsdManager = null
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_displaystream._tcp."
        private const val TAG = "TVCastNsd"
    }
}
