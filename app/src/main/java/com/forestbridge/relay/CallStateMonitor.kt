package com.forestbridge.relay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

private const val TAG = "ForestBridgeCallState"

enum class CallPhase(val wireValue: String) {
    RINGING("ringing"),
    ACTIVE("active"),
    ENDED("ended")
}

/**
 * Observes incoming cellular calls only.
 *
 * It intentionally ignores an OFFHOOK transition that was not preceded by
 * RINGING, so outgoing calls do not trigger a robot approach event.
 */
class CallStateMonitor(
    context: Context,
    private val onPhaseChanged: (CallPhase) -> Unit
) {
    private val appContext = context.applicationContext
    private val telephonyManager: TelephonyManager? =
        appContext.getSystemService(TelephonyManager::class.java)
    private val supportsCalling = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        appContext.packageManager.hasSystemFeature(
            "android.hardware.telephony.calling"
        )
    } else {
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    private var started = false
    private var sawIncomingRing = false
    private var lastPhase: CallPhase? = null
    private var modernCallback: Any? = null

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleState(state)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (started) return true
        val manager = telephonyManager
        if (!supportsCalling || manager == null) return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModern(manager)
            } else {
                @Suppress("DEPRECATION")
                manager.listen(
                    legacyListener,
                    PhoneStateListener.LISTEN_CALL_STATE
                )
            }
            started = true
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "Phone state permission unavailable", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Phone state monitoring unavailable", error)
            false
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startModern(manager: TelephonyManager) {
        val callback = object :
            TelephonyCallback(),
            TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleState(state)
            }
        }
        modernCallback = callback
        manager.registerTelephonyCallback(
            { runnable -> runnable.run() },
            callback
        )
    }

    fun stop() {
        if (!started) return
        started = false

        val manager = telephonyManager
        try {
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopModern(manager)
            } else if (manager != null) {
                @Suppress("DEPRECATION")
                manager.listen(
                    legacyListener,
                    PhoneStateListener.LISTEN_NONE
                )
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to unregister phone state monitoring", error)
        }

        sawIncomingRing = false
        lastPhase = null
    }

    @Suppress("NewApi")
    private fun stopModern(manager: TelephonyManager) {
        (modernCallback as? TelephonyCallback)?.let {
            manager.unregisterTelephonyCallback(it)
        }
        modernCallback = null
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                sawIncomingRing = true
                emit(CallPhase.RINGING)
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (sawIncomingRing) emit(CallPhase.ACTIVE)
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (sawIncomingRing) emit(CallPhase.ENDED)
                sawIncomingRing = false
                lastPhase = null
            }
        }
    }

    private fun emit(phase: CallPhase) {
        if (lastPhase == phase) return
        lastPhase = phase
        onPhaseChanged(phase)
    }
}
