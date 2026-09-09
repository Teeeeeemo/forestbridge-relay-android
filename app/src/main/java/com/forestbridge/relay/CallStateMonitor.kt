package com.forestbridge.relay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

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
    private val telephonyManager =
        context.applicationContext.getSystemService(TelephonyManager::class.java)

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
    fun start() {
        if (started) return
        started = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startModern()
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(
                legacyListener,
                PhoneStateListener.LISTEN_CALL_STATE
            )
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startModern() {
        val callback = object :
            TelephonyCallback(),
            TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleState(state)
            }
        }
        modernCallback = callback
        telephonyManager.registerTelephonyCallback(
            { runnable -> runnable.run() },
            callback
        )
    }

    fun stop() {
        if (!started) return
        started = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            stopModern()
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(
                legacyListener,
                PhoneStateListener.LISTEN_NONE
            )
        }

        sawIncomingRing = false
        lastPhase = null
    }

    @Suppress("NewApi")
    private fun stopModern() {
        (modernCallback as? TelephonyCallback)?.let {
            telephonyManager.unregisterTelephonyCallback(it)
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
