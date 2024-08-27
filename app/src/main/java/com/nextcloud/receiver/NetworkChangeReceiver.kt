/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Alper Ozturk <alper.ozturk@nextcloud.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.nextcloud.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nextcloud.client.jobs.BackgroundJobManager
import com.owncloud.android.MainApp
import javax.inject.Inject

class NetworkChangeReceiver : BroadcastReceiver() {

    @Inject
    lateinit var backgroundJobManager: BackgroundJobManager

    override fun onReceive(context: Context, intent: Intent?) {
        MainApp.setIsNetworkAvailable(isNetworkAvailable(context), backgroundJobManager)
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
