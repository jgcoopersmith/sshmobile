package com.j0ker.sshmobile

import android.app.Application
import com.j0ker.sshmobile.ssh.installFullBouncyCastle

class SshMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must happen before the first SSH handshake; see installFullBouncyCastle.
        installFullBouncyCastle()
    }
}
