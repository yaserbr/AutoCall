package com.yaser8541.autocallapp

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random
import java.util.Locale

data class DeviceIdentitySnapshot(
    val deviceUid: String,
    val deviceName: String
)

object DeviceIdentityStore {
    private const val PREFS_NAME = "autocall_device_identity_prefs"
    private const val KEY_DEVICE_UID = "device_uid"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val DEVICE_NAME_MAX_LENGTH = 60
    private const val DEVICE_UID_LENGTH = 5
    private const val DEVICE_NAME_PREFIX = "Device"
    private val uidLock = Any()

    private const val UID_RANDOM_CHARSET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val DEVICE_UID_REGEX = Regex("^[a-z0-9]{$DEVICE_UID_LENGTH}$")

    fun getOrCreateDeviceUid(context: Context): String {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)

        val existing = readValidStoredUid(prefs)
        if (existing.isNotEmpty()) {
            ensureDeviceNameStored(appContext, existing)
            return existing
        }

        synchronized(uidLock) {
            val doubleCheck = readValidStoredUid(prefs)
            if (doubleCheck.isNotEmpty()) {
                ensureDeviceNameStored(appContext, doubleCheck)
                return doubleCheck
            }

            val generatedUid = generateDeviceUid()
            prefs.edit()
                .putString(KEY_DEVICE_UID, generatedUid)
                .putString(KEY_DEVICE_NAME, buildDefaultDeviceName(generatedUid))
                .apply()
            return generatedUid
        }
    }

    fun getDeviceName(context: Context): String {
        val appContext = context.applicationContext
        val deviceUid = getOrCreateDeviceUid(appContext)
        val storedName = normalizeDeviceName(prefs(appContext).getString(KEY_DEVICE_NAME, null))
        return storedName ?: buildDefaultDeviceName(deviceUid)
    }

    fun setDeviceName(context: Context, deviceName: String?) {
        val appContext = context.applicationContext
        val deviceUid = getOrCreateDeviceUid(appContext)
        val normalized = normalizeDeviceName(deviceName) ?: buildDefaultDeviceName(deviceUid)
        prefs(appContext).edit().putString(KEY_DEVICE_NAME, normalized).apply()
    }

    fun syncFromServer(context: Context, deviceUid: String?, deviceName: String?) {
        val appContext = context.applicationContext
        val normalizedServerUid = normalizeUid(deviceUid)
        if (normalizedServerUid.isEmpty()) {
            return
        }

        val currentUid = getOrCreateDeviceUid(appContext)
        if (currentUid != normalizedServerUid) {
            return
        }

        val normalizedServerName = normalizeDeviceName(deviceName) ?: return
        prefs(appContext).edit().putString(KEY_DEVICE_NAME, normalizedServerName).apply()
    }

    fun snapshot(context: Context): DeviceIdentitySnapshot {
        val appContext = context.applicationContext
        val uid = getOrCreateDeviceUid(appContext)
        val name = getDeviceName(appContext)
        return DeviceIdentitySnapshot(
            deviceUid = uid,
            deviceName = name
        )
    }

    private fun ensureDeviceNameStored(context: Context, deviceUid: String) {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val existingName = normalizeDeviceName(prefs.getString(KEY_DEVICE_NAME, null))
        if (existingName != null) {
            return
        }
        prefs.edit().putString(KEY_DEVICE_NAME, buildDefaultDeviceName(deviceUid)).apply()
    }

    private fun generateDeviceUid(): String {
        return buildString {
            repeat(DEVICE_UID_LENGTH) {
                append(UID_RANDOM_CHARSET[Random.nextInt(UID_RANDOM_CHARSET.length)])
            }
        }
    }

    private fun buildDefaultDeviceName(deviceUid: String): String {
        val suffix = deviceUid
            .filter { it.isLetterOrDigit() }
            .takeLast(4)
            .uppercase()
            .padStart(4, '0')
        return "${DEVICE_NAME_PREFIX}-${suffix}"
    }

    private fun normalizeUid(value: String?): String {
        val trimmed = value?.trim().orEmpty().lowercase(Locale.US)
        return if (DEVICE_UID_REGEX.matches(trimmed)) trimmed else ""
    }

    private fun readValidStoredUid(prefs: SharedPreferences): String {
        val rawUid = prefs.getString(KEY_DEVICE_UID, null)
        val normalizedUid = normalizeUid(rawUid)
        if (normalizedUid.isNotEmpty()) {
            if (rawUid != normalizedUid) {
                prefs.edit().putString(KEY_DEVICE_UID, normalizedUid).apply()
            }
            return normalizedUid
        }

        if (!rawUid.isNullOrBlank()) {
            prefs.edit()
                .remove(KEY_DEVICE_UID)
                .remove(KEY_DEVICE_NAME)
                .apply()
        }
        return ""
    }

    private fun normalizeDeviceName(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        return trimmed.take(DEVICE_NAME_MAX_LENGTH)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
