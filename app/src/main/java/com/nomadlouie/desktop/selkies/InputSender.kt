package com.nomadlouie.desktop.selkies

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Sends keyboard input as Selkies input verbs, tracking held keys so a `kh`
 * heartbeat repeats them (the server releases anything no longer listed,
 * healing a lost keyup) and `kr` clears everything.
 */
class InputSender(private val client: SelkiesClient) {

    companion object {
        private const val TAG = "InputSender"
        private const val HEARTBEAT_MS = 2000L
        private const val TYPE_PACE_MS = 12L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val heldKeys = LinkedHashMap<Int, Int>() // physical-ish id -> keysym
    private val sticky = LinkedHashSet<Int>()        // sticky modifier keysyms

    private val heartbeat = object : Runnable {
        override fun run() {
            if (heldKeys.isEmpty() && sticky.isEmpty()) return
            client.keysHeld((sticky + heldKeys.values).toList())
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun pressKey(keysym: Int, keyId: Int = keysym) {
        if (heldKeys.containsKey(keyId)) return
        heldKeys[keyId] = keysym
        client.keyDown(keysym)
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    fun releaseKey(keyId: Int) {
        val keysym = heldKeys.remove(keyId) ?: return
        client.keyUp(keysym)
        if (heldKeys.isEmpty() && sticky.isEmpty()) {
            handler.removeCallbacks(heartbeat)
            client.keysHeld(emptyList())
        }
    }

    fun releaseAll() {
        for ((_, keysym) in heldKeys) client.keyUp(keysym)
        heldKeys.clear()
        clearSticky()
        handler.removeCallbacks(heartbeat)
        client.keysHeld(emptyList())
    }

    /** Sticky modifier toggles for the on-screen buttons. */
    fun toggleSticky(keysym: Int): Boolean {
        if (!sticky.remove(keysym)) {
            sticky.add(keysym)
            client.keyDown(keysym)
        } else {
            client.keyUp(keysym)
        }
        return sticky.contains(keysym)
    }

    fun hasSticky(keysym: Int): Boolean = sticky.contains(keysym)

    fun clearSticky() {
        for (m in sticky) client.keyUp(m)
        sticky.clear()
    }

    /**
     * Types text as momentary key presses. Uppercase letters are sent with
     * their uppercase keysym, which the server maps directly to the character.
     */
    fun typeText(text: String) {
        val chars = text.mapNotNull { Keysyms.fromUnicode(it.code) }
        if (chars.isEmpty()) return
        var i = 0
        val step = object : Runnable {
            override fun run() {
                if (i >= chars.size) return
                val ks = chars[i]
                client.keyDown(ks)
                client.keyUp(ks)
                i++
                handler.postDelayed(this, TYPE_PACE_MS)
            }
        }
        handler.post(step)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }
}
