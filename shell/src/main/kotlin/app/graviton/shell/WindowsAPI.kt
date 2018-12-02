package app.graviton.shell

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.W32APIOptions
import java.io.*

@Suppress("FunctionName", "unused")
private interface MyUser32 : User32 {
    companion object {
        val INSTANCE = Native.loadLibrary("user32", MyUser32::class.java, W32APIOptions.UNICODE_OPTIONS) as MyUser32

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
    MyUser32.INSTANCE.MessageBox(WinDef.HWND(null), WString(content), WString(title), MyUser32.MB_OK.toInt())
}

private const val ENABLE_PROCESSED_OUTPUT = 1
private const val ENABLE_VIRTUAL_TERMINAL_PROCESSING = 4
private const val TERMINAL_FLAGS = ENABLE_PROCESSED_OUTPUT or ENABLE_VIRTUAL_TERMINAL_PROCESSING

@Suppress("FunctionName")
private interface MyKernel32 : Kernel32 {
    fun WriteConsole(handle: WinNT.HANDLE, lpBuffer: ByteArray, nNumberOfCharsToWrite :Int, lpNumberOfBytesWritten: IntByReference, lpReserved: Pointer): Boolean
}

fun configureWindowsConsole(): Boolean {
    // Windows EXEs can be marked either as a console OR a gui app, but not both. Console apps can be invoked from
    // the cmd.com shell successfully but when run from Explorer or the start menu pop up a console window which
    // we don't want. GUI apps however can't print anything or read keyboard input if run from the console.
    //
    // This sad, nay pathetic, situation is discussed here: https://blogs.msdn.microsoft.com/oldnewthing/20090101-00/?p=19643
    //
    // We utilise a set of hacks that is almost but not quite as good as a real fix. The only remaining problem is
    // you can't redirect stdout from Graviton to a file. To fix this will require more magic, like tunnelling
    // sub-process handles through pipes to the parent process which seems to be a popular solution. Alternatively
    // we can perhaps do something with cloning handles.
    //
    // Anyway, the code below does the following with the Windows API:
    //
    // * Attaches to the parent console. This means we can now read and write to the console. And because the bootstrapper
    //   program has a variant marked as a console app (so the shell waits for it) and it itself waits for us to quit,
    //   this effectively gives us control over the console.
    // * Windows kernel CLOSES and NULLS OUT the stdin/stdout handles for a GUI app even if launched from the console.
    //   We re-open them here using the magic file names to get the handles and reset them.
    // * Unfortunately by the time we reach here it's too late, java.lang.System was initialised already and the
    //   java.io.FileDescriptor.in/out/err handles were initialised, with System.in/out/err wrapping them. So we use
    //   an internal Java API to re-initialise the handles and then the System io streams. We are now reconnected to
    //   the parent console.
    // * Finally we enable ANSI terminal emulation mode for Windows 10. This lets programs use a uniform UNIX API
    //   to draw to the console.

    try {
        val kernel32 = Native.loadLibrary("kernel32", MyKernel32::class.java, W32APIOptions.UNICODE_OPTIONS) as MyKernel32
        if (!kernel32.AttachConsole(Wincon.ATTACH_PARENT_PROCESS)) {
            // Launched from the Windows GUI or the installer.
            return false
        }

        // 0x80 - FILE_ATTRIBUTE_NORMAL
        // In theory we should open these with READ and WRITE permissions respectively, but some software (like jline)
        // really wants to call SetConsoleMode on CONIN$ so we have to request read/write perms for both handles.
        fun openConOut(): WinNT.HANDLE =
                kernel32.CreateFile("CONOUT$", WinNT.GENERIC_READ or WinNT.GENERIC_WRITE, WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0x80, null)
        kernel32.SetStdHandle(-10, kernel32.CreateFile("CONIN$", WinNT.GENERIC_READ or WinNT.GENERIC_WRITE, WinNT.FILE_SHARE_READ, null, WinNT.OPEN_EXISTING, 0x80, null))
        val hStdOut = openConOut()
        val hStdErr = openConOut()
        kernel32.SetStdHandle(-11, hStdOut)  // standard out
        kernel32.SetStdHandle(-12, hStdErr)  // standard error

        val fdClass = FileDescriptor::class.java
        val standardStream = fdClass.getDeclaredMethod("standardStream", Int::class.java)
        standardStream.isAccessible = true
        val inFd = standardStream.invoke(null, 0) as FileDescriptor
        System.setIn(BufferedInputStream(FileInputStream(inFd)))
        val outFd = standardStream.invoke(null, 1) as FileDescriptor
        System.setOut(PrintStream(FileOutputStream(outFd)))
        val errFd = standardStream.invoke(null, 2) as FileDescriptor
        System.setErr(PrintStream(FileOutputStream(errFd)))

        // Activate ANSI handling.
        val curMode = IntByReference()
        if (!kernel32.GetConsoleMode(hStdOut, curMode))
            println("GetConsoleMode error: " + kernel32.GetLastError())
        val oldValue = curMode.value
        if (!kernel32.SetConsoleMode(hStdOut, TERMINAL_FLAGS))
            println("SetConsoleMode error: " + kernel32.GetLastError())
        Runtime.getRuntime().addShutdownHook(Thread {
            if (!kernel32.SetConsoleMode(hStdOut, oldValue))
                println("SetConsoleMode for restore error: " + kernel32.GetLastError())
        })
        return true
    } catch (e: Exception) {
        windowsAlertBox("Error during setup", e.toString())
        return false
    }
}