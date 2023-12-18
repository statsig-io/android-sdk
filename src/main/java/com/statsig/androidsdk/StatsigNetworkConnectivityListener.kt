package com.statsig.androidsdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

class StatsigNetworkConnectivityListener(
    context: Context,
) {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isNetworkAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            return networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

        return connectivityManager.activeNetworkInfo?.isConnectedOrConnecting == true
    }
}
