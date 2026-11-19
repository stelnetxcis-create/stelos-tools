/*
 * SPDX-FileCopyrightText: 2014 Samoilenko Yuri <kinnalru@gmail.com>
 * SPDX-FileCopyrightText: 2024 ShellWen Chen <me@shellwen.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 * SPDX-FileContributor: ii tools: from KDE Connect's SimpleSftpServer and SftpPlugin, used
 *   under GPL-3.0-only. One object started by the ii link (`files`) instead of a plugin,
 *   native storage only (Android 11+), no SCP, no MINA, an own EC host key whose public
 *   half the PC pins, password only.
 */
package dev.ii.tools.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import dev.ii.tools.R
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.channels.Channel
import java.nio.file.CopyOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The phone's storage for the PC's file manager (PROTOCOL.md, "Files on the phone"): an
 * SFTP server that runs only while the PC has it mounted. The PC asks over the link,
 * gets a fresh password and the host key there (both over the link's TLS), and mounts
 * with sshfs; the server stops when the PC lets go or the link drops.
 */
object FileServer {
    private const val PREFS = "files"
    private const val ENABLED = "enabled"
    private const val CHANNEL = "files"
    private const val NOTIF_ID = 0x6669
    private const val USER = "iitools"
    private val PORTS = 1739..1764

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: SshServer? = null
    private val storage = NativeStorageFileSystemFactory()
    private val auth = Password()

    init {
        System.setProperty(SecurityUtils.SECURITY_PROVIDER_REGISTRARS, "") // no BouncyCastle
        System.setProperty("org.apache.sshd.common.io.IoServiceFactoryFactory", "org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory")
        // Android has no home folder, which SSHD looks up.
        PathUtils.setUserHomeFolderResolver { Path.of("/") }
    }

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
        if (!on) stop()
        LinkHub.announceCaps()
    }

    fun caps(c: Context): List<String> = if (isEnabled(c)) listOf("files") else emptyList()

    fun hasPermission(): Boolean = Environment.isExternalStorageManager()

    fun permissionIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))

    /** `{"t":"files","a":"start"|"stop"}` from the PC. */
    fun command(c: Context, m: JSONObject) {
        val ctx = c.applicationContext
        when (m.optString("a")) {
            "start" -> scope.launch { start(ctx) }
            "stop" -> stop()
        }
    }

    @Synchronized
    private fun start(ctx: Context) {
        if (!isEnabled(ctx)) return reply(JSONObject().put("err", "disabled"))
        if (!hasPermission()) {
            askPermission(ctx)
            return reply(JSONObject().put("err", "permission"))
        }
        val volumes = ctx.getSystemService(StorageManager::class.java).storageVolumes
            .mapNotNull { v -> v.directory?.let { it.path to v.getDescription(ctx) } }
        if (volumes.isEmpty()) return reply(JSONObject().put("err", "no_storage"))
        storage.storageDirs = volumes.map { Path.of(it.first) }

        val keys = hostKey(ctx)
        val s = server?.takeIf { it.isStarted } ?: build(ctx, keys).also { sshd ->
            val started = PORTS.any { port ->
                try {
                    sshd.port = port
                    sshd.start()
                    true
                } catch (_: IOException) {
                    false
                }
            }
            if (!started) return reply(JSONObject().put("err", "failed"))
            server = sshd
        }
        reply(
            JSONObject()
                .put("port", s.port)
                .put("user", USER)
                .put("password", auth.renew())
                .put("hostKey", PublicKeyEntry.toString(keys.public))
                .put("paths", JSONArray(volumes.map { it.first }))
                .put("names", JSONArray(volumes.map { it.second })),
        )
    }

    /** The PC let go, or the link dropped. */
    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        scope.launch { runCatching { s.stop(true) } }
    }

    private fun reply(m: JSONObject) {
        LinkHub.sendJson(m.put("t", "files"))
    }

    private fun build(ctx: Context, keys: KeyPair): SshServer {
        val sshd = ServerBuilder.builder().fileSystemFactory(storage).build()
        sshd.keyPairProvider = KeyPairProvider.wrap(keys)
        sshd.passwordAuthenticator = auth
        sshd.subsystemFactories = listOf(
            SftpSubsystemFactory.Builder().withFileSystemAccessor(MediaAware(ctx)).build(),
        )
        return sshd
    }

    /** What the PC writes, moves or deletes shows up in the gallery and other apps. */
    private class MediaAware(private val ctx: Context) : SftpFileSystemAccessor {
        private fun scan(vararg paths: Path?) {
            val list = paths.filterNotNull().map { it.toString() }.toTypedArray()
            if (list.isNotEmpty()) MediaScannerConnection.scanFile(ctx, list, null, null)
        }

        override fun removeFile(subsystem: SftpSubsystemProxy?, path: Path?, isDirectory: Boolean) {
            super.removeFile(subsystem, path, isDirectory)
            scan(path)
        }

        override fun renameFile(subsystem: SftpSubsystemProxy?, oldPath: Path?, newPath: Path?, opts: MutableCollection<CopyOption>?) {
            super.renameFile(subsystem, oldPath, newPath, opts)
            scan(oldPath, newPath)
        }

        override fun copyFile(subsystem: SftpSubsystemProxy?, src: Path?, dst: Path?, opts: MutableCollection<CopyOption>?) {
            super.copyFile(subsystem, src, dst, opts)
            scan(dst)
        }

        override fun closeFile(
            subsystem: SftpSubsystemProxy?,
            fileHandle: FileHandle?,
            file: Path?,
            handle: String?,
            channel: Channel?,
            options: MutableSet<out OpenOption>?,
        ) {
            super.closeFile(subsystem, fileHandle, file, handle, channel, options)
            if (options?.contains(StandardOpenOption.WRITE) == true) scan(file)
        }
    }

    /** One password per start, compared by hash in constant time. */
    private class Password : PasswordAuthenticator {
        @Volatile private var hash = ByteArray(0)

        fun renew(): String {
            val bytes = ByteArray(21).also { SecureRandom().nextBytes(it) }
            val password = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
            hash = sha(password)
            return password
        }

        override fun authenticate(user: String, password: String, session: org.apache.sshd.server.session.ServerSession): Boolean =
            user == USER && hash.isNotEmpty() && MessageDigest.isEqual(hash, sha(password))

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    }

    /** The server's own key, made once; the PC pins its public half per mount. */
    private fun hostKey(ctx: Context): KeyPair {
        val priv = File(ctx.filesDir, "sftp_host.key")
        val pub = File(ctx.filesDir, "sftp_host.pub")
        val kf = KeyFactory.getInstance("EC")
        runCatching {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        priv.writeBytes(pair.private.encoded)
        pub.writeBytes(pair.public.encoded)
        return pair
    }

    /** Asked from the PC without the permission: a tap here grants it. */
    private fun askPermission(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.files_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, permissionIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            NOTIF_ID,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.rounded_folder_open)
                .setContentTitle(ctx.getString(R.string.files_permission_title))
                .setContentText(ctx.getString(R.string.files_permission_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
