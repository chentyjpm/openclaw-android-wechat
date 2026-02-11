package com.ws.wx_server.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import com.ws.wx_server.util.Logger

class LanBotImeService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Logger.i("LanBot IME created", tag = "LanBotIME")
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Logger.i("LanBot IME start input: pkg=${attribute?.packageName} restarting=$restarting", tag = "LanBotIME")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Logger.i("LanBot IME finish input", tag = "LanBotIME")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        Logger.i("LanBot IME destroyed", tag = "LanBotIME")
    }

    private fun sendTabInternal(): Boolean {
        val ic = currentInputConnection ?: run {
            Logger.i("IME send TAB skipped: inputConnection null", tag = "LanBotIME")
            return false
        }
        return try {
            val down = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            val up = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
            val ok = down && up
            Logger.i("IME send TAB result down=$down up=$up", tag = "LanBotIME")
            if (ok) true else ic.commitText("\t", 1)
        } catch (t: Throwable) {
            Logger.w("IME send TAB exception: ${t.message}", tag = "LanBotIME")
            false
        }
    }

    private fun sendEnterInternal(): Boolean {
        val ic = currentInputConnection ?: run {
            Logger.i("IME send ENTER skipped: inputConnection null", tag = "LanBotIME")
            return false
        }
        return try {
            val down = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            val up = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            Logger.i("IME send ENTER result down=$down up=$up", tag = "LanBotIME")
            down && up
        } catch (t: Throwable) {
            Logger.w("IME send ENTER exception: ${t.message}", tag = "LanBotIME")
            false
        }
    }

    private fun commitTextInternal(text: String): Boolean {
        if (text.isEmpty()) {
            Logger.i("IME commit skipped: empty text", tag = "LanBotIME")
            return false
        }
        val ic = currentInputConnection ?: run {
            Logger.i("IME commit skipped: inputConnection null", tag = "LanBotIME")
            return false
        }
        return try {
            val ok = ic.commitText(text, 1)
            Logger.i("IME commit text result=$ok len=${text.length}", tag = "LanBotIME")
            ok
        } catch (t: Throwable) {
            Logger.w("IME commit exception: ${t.message}", tag = "LanBotIME")
            false
        }
    }

    companion object {
        @Volatile
        private var instance: LanBotImeService? = null

        fun isServiceActive(): Boolean = instance != null

        fun sendTabFromService(): Boolean {
            val ime = instance ?: run {
                Logger.i("IME send TAB skipped: service inactive", tag = "LanBotIME")
                return false
            }
            return ime.sendTabInternal()
        }

        fun sendEnterFromService(): Boolean {
            val ime = instance ?: run {
                Logger.i("IME send ENTER skipped: service inactive", tag = "LanBotIME")
                return false
            }
            return ime.sendEnterInternal()
        }

        fun commitTextFromService(text: String): Boolean {
            val ime = instance ?: run {
                Logger.i("IME commit skipped: service inactive", tag = "LanBotIME")
                return false
            }
            return ime.commitTextInternal(text)
        }
    }
}
