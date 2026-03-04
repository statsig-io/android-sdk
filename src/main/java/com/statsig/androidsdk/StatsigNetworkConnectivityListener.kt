package com.statsig.androidsdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.os.RemoteException
import android.util.Log

class StatsigNetworkConnectivityListener(context: Context) {

    companion object {
        const val TAG = "statsig::NetListener"

        const val NETWORK_METADATA_NET_TYPE = "netType"
        const val NETWORK_METADATA_HAS_INTERNET = "hasInternet"

        enum class NetType {
            CELL,
            WIFI,
            USB,
            BLUETOOTH,
            ETHERNET,
            SATELLITE,
            VPN,
            NONE
        }
    }
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isNetworkAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            return networkCapabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) ==
                true
        }

        @Suppress("DEPRECATION")
        return connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true
    }

    fun getLogEventNetworkMetadata(): Map<String, String> {
        // Older versions of Android don't get this network metadata
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var netType = NetType.NONE
            var hasInternet = false
            try {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val networkCapabilities =
                        connectivityManager.getNetworkCapabilities(activeNetwork)
                    networkCapabilities?.let {
                        // check for functional internet access
                        hasInternet =
                            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            it.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED
                            )
                        netType = getNetType(it)

                        // Things that might warrant adding later
//                        val metered = it.capabilities.contains(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
//                        val upstreamBandwidth = it.linkUpstreamBandwidthKbps
//                        val downstreamBandwidth = it.linkDownstreamBandwidthKbps
//                        val isNotVPN = it.capabilities.contains(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
//                        val isVPNTransport = it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

                        // Signal strength for CELL and WIFI comes as RSSI dBm
                        // -30 to -70 is excellent
                        // -71 to -80 is good
                        // -81 to -90 is fair
                        // -91 and below is weak/poor
//                        val signalStrength = it.signalStrength
                    }
                }
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to acquire network metadata", e)
            }

            val retMap = mutableMapOf<String, String>()
            retMap[NETWORK_METADATA_NET_TYPE] = netType.name.lowercase()
            retMap[NETWORK_METADATA_HAS_INTERNET] = hasInternet.toString()
            return retMap
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo

            @Suppress("DEPRECATION")
            val hasInternet = activeNetworkInfo?.isConnectedOrConnecting
            val netType = getLegacyNetType(activeNetworkInfo)

            val retMap = mutableMapOf<String, String>()
            retMap[NETWORK_METADATA_NET_TYPE] = netType.name.lowercase()
            retMap[NETWORK_METADATA_HAS_INTERNET] = hasInternet.toString()
            return retMap
        }
    }

    private fun getNetType(netCaps: NetworkCapabilities): NetType {
        var netType = NetType.NONE
        // check transport types
        if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            netType = NetType.WIFI
        } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            netType = NetType.CELL
        } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            netType = NetType.ETHERNET
        } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
            netType = NetType.USB
        } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            netType = NetType.BLUETOOTH
        } else if (netCaps.hasTransport(10)) {
            // TRANSPORT_SATELLITE = 10 in Android API 35+
            netType = NetType.SATELLITE
        } else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            netType = NetType.VPN
        }
        return netType
    }

    @Suppress("DEPRECATION")
    private fun getLegacyNetType(activeNetworkInfo: NetworkInfo?): NetType =
        when (activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_WIFI -> NetType.WIFI
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_MOBILE_DUN,
            ConnectivityManager.TYPE_MOBILE_HIPRI,
            ConnectivityManager.TYPE_MOBILE_MMS,
            ConnectivityManager.TYPE_MOBILE_SUPL -> NetType.CELL
            ConnectivityManager.TYPE_ETHERNET -> NetType.ETHERNET
            ConnectivityManager.TYPE_BLUETOOTH -> NetType.BLUETOOTH
            ConnectivityManager.TYPE_VPN -> NetType.VPN
            else -> NetType.NONE
        }
}
