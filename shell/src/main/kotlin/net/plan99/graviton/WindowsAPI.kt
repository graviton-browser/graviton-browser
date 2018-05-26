package net.plan99.graviton

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.W32APIOptions
import net.plan99.graviton.MyUser32.Companion.MB_OK

@Suppress("FunctionName")
private interface MyUser32 : User32 {
    companion object {
        val INSTANCE = Native.loadLibrary("user32", MyUser32::class.java, W32APIOptions.DEFAULT_OPTIONS) as MyUser32

        // Message box constants.
        const val MB_OK = 0x00000000L
        const val MB_OKCANCEL = 0x00000001L
        const val MB_ABORTRETRYIGNORE = 0x00000002L
        const val MB_YESNOCANCEL = 0x00000003L
        const val MB_YESNO = 0x00000004L
        const val MB_RETRYCANCEL = 0x00000005L
        const val MB_CANCELTRYCONTINUE = 0x00000006L
        const val MB_ICONHAND = 0x00000010L
        const val MB_ICONQUESTION = 0x00000020L
        const val MB_ICONEXCLAMATION = 0x00000030L
        const val MB_ICONASTERISK = 0x00000040L
        const val MB_USERICON = 0x00000080L
        const val MB_ICONWARNING = MB_ICONEXCLAMATION
        const val MB_ICONERROR = MB_ICONHAND
        const val MB_ICONINFORMATION = MB_ICONASTERISK
        const val MB_ICONSTOP = MB_ICONHAND
    }

    fun MessageBox(hWnd: WinDef.HWND, lpText: WString, lpCaption: WString, uType: Int): Int
}

fun windowsAlertBox(title: String, content: String) {
    MyUser32.INSTANCE.MessageBox(WinDef.HWND(null), WString(content), WString(title), MB_OK.toInt())
}