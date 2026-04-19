package com.yaser8541.autocallapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class AutoCallPhoneStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AutoCall/PhoneReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.i(TAG, "phone_state_changed state=$state")
        AutoAnswerController.onPhoneStateChanged(context, state)
    }
}

