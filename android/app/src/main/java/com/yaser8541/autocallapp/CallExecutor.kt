package com.yaser8541.autocallapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

class AutoCallException(val code: String, message: String) : IllegalStateException(message)

data class CallExecutionResult(
    val action: String,
    val phoneNumber: String,
    val usedCurrentActivity: Boolean,
    val timestamp: Long
)

object CallExecutor {
    fun placeCall(context: Context, rawNumber: String, activity: Activity? = null): CallExecutionResult {
        val phoneNumber = normalizePhoneNumber(rawNumber)

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            throw AutoCallException("E_PERMISSION_DENIED", "CALL_PHONE permission is not granted")
        }

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
        }

        val canResolve = callIntent.resolveActivity(context.packageManager) != null
        if (!canResolve) {
            throw AutoCallException("E_NO_CALL_ACTIVITY", "No app can resolve ACTION_CALL")
        }

        val usedCurrentActivity = activity != null
        if (activity != null) {
            activity.startActivity(callIntent)
        } else {
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(callIntent)
        }

        return CallExecutionResult(
            action = Intent.ACTION_CALL,
            phoneNumber = phoneNumber,
            usedCurrentActivity = usedCurrentActivity,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun normalizePhoneNumber(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw AutoCallException("E_INVALID_NUMBER", "Phone number is empty")
        }

        val normalized = trimmed.replace(Regex("[^\\d+]"), "")
        val plusCount = normalized.count { it == '+' }
        if (plusCount > 1 || (plusCount == 1 && !normalized.startsWith("+"))) {
            throw AutoCallException("E_INVALID_NUMBER", "Phone number format is invalid")
        }

        val digits = normalized.replace("+", "")
        if (digits.isEmpty()) {
            throw AutoCallException("E_INVALID_NUMBER", "Phone number does not contain digits")
        }

        return normalized
    }
}

