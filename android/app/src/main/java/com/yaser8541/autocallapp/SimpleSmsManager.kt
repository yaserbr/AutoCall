package com.yaser8541.autocallapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

data class SimpleSmsSendResult(
    val success: Boolean,
    val reason: String,
    val message: String,
    val phoneNumber: String? = null,
    val textLength: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object SimpleSmsManager {
    private const val TAG = "AutoCall/SimpleSms"
    private const val SEND_SMS_PERMISSION_DENIED_CODE = "E_SEND_SMS_PERMISSION_DENIED"
    private const val SEND_SMS_PERMISSION_DENIED_MESSAGE = "SEND_SMS permission denied"

    fun sendServerCommandSms(
        context: Context,
        rawPhoneNumber: String,
        rawMessage: String
    ): SimpleSmsSendResult {
        val appContext = context.applicationContext
        val normalizedPhone = normalizePhoneNumber(rawPhoneNumber) ?: run {
            return SimpleSmsSendResult(
                success = false,
                reason = "invalid_number",
                message = "Phone number is empty or invalid"
            )
        }

        val normalizedMessage = normalizeMessage(rawMessage) ?: run {
            return SimpleSmsSendResult(
                success = false,
                reason = "invalid_message",
                message = "SMS message is empty",
                phoneNumber = normalizedPhone
            )
        }

        val hasSendSmsPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasSendSmsPermission) {
            throw AutoCallException(
                code = SEND_SMS_PERMISSION_DENIED_CODE,
                message = SEND_SMS_PERMISSION_DENIED_MESSAGE
            )
        }

        val smsManager = resolveSmsManager(appContext)
        if (smsManager == null) {
            return SimpleSmsSendResult(
                success = false,
                reason = "sms_manager_unavailable",
                message = "SmsManager is not available on this device",
                phoneNumber = normalizedPhone,
                textLength = normalizedMessage.length
            )
        }

        return try {
            val parts = smsManager.divideMessage(normalizedMessage)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(
                    normalizedPhone,
                    null,
                    parts,
                    null,
                    null
                )
            } else {
                smsManager.sendTextMessage(normalizedPhone, null, normalizedMessage, null, null)
            }

            Log.i(
                TAG,
                "sendServerCommandSms success phone=$normalizedPhone length=${normalizedMessage.length}"
            )
            SimpleSmsSendResult(
                success = true,
                reason = "sent",
                message = "SMS sent successfully",
                phoneNumber = normalizedPhone,
                textLength = normalizedMessage.length
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "sendServerCommandSms security exception", error)
            SimpleSmsSendResult(
                success = false,
                reason = "security_exception",
                message = error.message ?: "SecurityException while sending SMS",
                phoneNumber = normalizedPhone,
                textLength = normalizedMessage.length
            )
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "sendServerCommandSms illegal argument", error)
            SimpleSmsSendResult(
                success = false,
                reason = "invalid_arguments",
                message = error.message ?: "Invalid SMS arguments",
                phoneNumber = normalizedPhone,
                textLength = normalizedMessage.length
            )
        } catch (error: Throwable) {
            Log.e(TAG, "sendServerCommandSms failed", error)
            SimpleSmsSendResult(
                success = false,
                reason = "send_failed",
                message = error.message ?: "Unexpected error while sending SMS",
                phoneNumber = normalizedPhone,
                textLength = normalizedMessage.length
            )
        }
    }

    private fun resolveSmsManager(context: Context): SmsManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: run {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun normalizePhoneNumber(rawNumber: String): String? {
        val trimmed = rawNumber.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val normalized = trimmed.replace(Regex("[^\\d+]"), "")
        val plusCount = normalized.count { it == '+' }
        if (plusCount > 1 || (plusCount == 1 && !normalized.startsWith("+"))) {
            return null
        }

        val digits = normalized.replace("+", "")
        if (digits.isEmpty()) {
            return null
        }

        return normalized
    }

    private fun normalizeMessage(rawMessage: String): String? {
        val trimmed = rawMessage.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return trimmed
    }
}
