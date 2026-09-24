package com.openminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Observable state for rootfs installation. Mirrors the conceptual iOS
 * `downloadRootfs(progress:)` contract: discrete phases with a 0..1 fraction
 * during the long-running extract step. Neither platform actually downloads
 * the rootfs from the network today — it ships bundled as an asset — so
 * "progress" tracks asset-stream consumption (compressed bytes) instead of
 * HTTP transfer. The fraction semantics are identical from the UI's viewpoint.
 */
sealed class RootfsInstallState {
    object Idle : RootfsInstallState()
    object Preparing : RootfsInstallState()
    /** progress in 0f..1f, based on compressed asset bytes consumed. */
    data class Extracting(val progress: Float) : RootfsInstallState()
    object Finalizing : RootfsInstallState()
    object Installed : RootfsInstallState()
    data class Failed(val error: String) : RootfsInstallState()
}

/**
 * Manages Ubuntu Linux rootfs installation and PRoot binary extraction.
 * Corresponds to iOS RootfsManager.swift.
 */
class RootfsManager private constructor(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "ubuntu-rootfs")
    val prootBinary: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    private val archFile: File get() = File(rootfsDir, ".arch")
    private val distroFile: File get() = File(rootfsDir, ".distro")

    val isInstalled: Boolean
        get() {
            adoptLegacyDistroMarker()
            return rootfsDir.exists() && archFile.exists() &&
                    archFile.readText().trim() == ARCH &&
                    distroFile.exists() && distroFile.readText().trim() == DISTRO
        }

    /**
     * Builds before the distro marker only wrote `.arch`. [installIfNeeded]
     * treats "not installed" as a partial extract and deletes the whole tree,
     * which on upgrade wipes `/root`, apt state and anything the user installed.
     * A matching arch plus a real guest (`usr/bin` or `bin`) is an old Ubuntu
     * rootfs, not a partial extract — stamp the marker and keep it.
     */
    private fun adoptLegacyDistroMarker() {
        if (!rootfsDir.isDirectory || !archFile.isFile || distroFile.exists()) return
        val archMatches = runCatching { archFile.readText().trim() == ARCH }.getOrDefault(false)
        val guest = File(rootfsDir, "usr/bin").isDirectory || File(rootfsDir, "bin").isDirectory
        if (!RootfsUpgradePolicy.shouldAdoptMissingDistroMarker(archMatches, guestTreePresent = guest)) return
        runCatching {
            distroFile.writeText(DISTRO)
            Log.i(TAG, "stamped missing .distro on existing Ubuntu rootfs; not reinstalling")
        }
    }

    /**
     * Observable install progress. UI layers (OnboardingScreen,
     * RootfsManagementScreen) bind this and render a progress bar during
     * `installIfNeeded()` / `reset()`. Emits `Installed` on success and
     * `Failed` on error so callers can surface retry affordances.
     */
    private val _installState = MutableStateFlow<RootfsInstallState>(
        if (isInstalled) RootfsInstallState.Installed else RootfsInstallState.Idle
    )
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    /**
     * Install Ubuntu rootfs from assets if not already present.
     * Extracts ubuntu-base.tar.gz using manual POSIX tar parsing.
     * Progress is published to [installState] (Preparing → Extracting(f) →
     * Finalizing → Installed / Failed).
     */
    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (isInstalled) {
            Log.d(TAG, "Rootfs already installed at $rootfsDir")
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }

        try {
            _installState.value = RootfsInstallState.Preparing
            Log.i(TAG, "Installing Ubuntu rootfs...")

            // Clean up any partial install
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            // Extract rootfs from assets.
            // AAPT may decompress .tar.gz → .tar automatically, so try both names.
            val assetName = try {
                context.assets.open(ROOTFS_ASSET).close()
                ROOTFS_ASSET
            } catch (_: java.io.FileNotFoundException) {
                ROOTFS_ASSET_TAR
            }

            // Asset size for progress calculation — compressed length (for .gz)
            // or uncompressed length (for .tar). openFd() fails for 0-length
            // assets on some devices; fall back to 0 which disables progress.
            val assetTotal: Long = try {
                context.assets.openFd(assetName).use { it.length }
            } catch (_: Exception) { 0L }

            // Emit an initial 0% so the UI flips from Preparing → progress bar.
            _installState.value = RootfsInstallState.Extracting(0f)

            context.assets.open(assetName).use { rawAsset ->
                // Wrap the ASSET stream (not the gzip stream) so progress tracks
                // compressed bytes consumed — monotonic and matches the size we
                // have a total for. Throttle updates to avoid flooding the StateFlow.
                val progressStream = ProgressInputStream(rawAsset, assetTotal) { fraction ->
                    _installState.value = RootfsInstallState.Extracting(fraction)
                }
                if (assetName.endsWith(".gz")) {
                    GZIPInputStream(progressStream).use { gzipStream ->
                        extractTar(gzipStream, rootfsDir)
                    }
                } else {
                    extractTar(progressStream, rootfsDir)
                }
            }

            _installState.value = RootfsInstallState.Finalizing

            // Write arch marker
            archFile.writeText(ARCH)
            distroFile.writeText(DISTRO)
            configureUbuntuGuest()
            deleteLegacyAlpineRootfs()

            // Pre-create /var/minis directories. Mirrors iOS
            // RootfsManager.swift:76-80 (attachments/offloads/workspace/skills/
            // shared) plus Android-specific `memory` kept from prior parity work.
            // T219-6: also pre-create `mounts/` so PRoot's `-b host:/var/minis/mounts/<name>`
            // has the parent directory to bind into; without this, PRoot silently
            // skips bind mounts whose target path doesn't exist.
            val minisSubdirs = listOf("attachments", "offloads", "workspace", "skills", "memory", "shared", "mounts")
            for (subdir in minisSubdirs) {
                File(rootfsDir, "var/minis/$subdir").mkdirs()
            }

            // Pre-create /opt/bin — appears in PATH so users can drop third-party
            // binaries here without first `mkdir -p`. Matches iOS PATH layout.
            File(rootfsDir, "opt/bin").mkdirs()

            // Write resolv.conf from system DNS (fallback to 8.8.8.8)
            refreshDns()

            // Flag a fresh install so MirrorSpeedTestViewModel.autoDetectOnceIfNeeded()
            // picks the fastest mirror for each category on first boot.
            context.getSharedPreferences("mirror_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("rootfs.freshInstall", true)
                .apply()

            Log.i(TAG, "Rootfs installation complete")
            _installState.value = RootfsInstallState.Installed
        } catch (t: Throwable) {
            Log.e(TAG, "Rootfs installation failed", t)
            _installState.value = RootfsInstallState.Failed(t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    /** Directory containing extracted native libraries (read-only, executable). */
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    /**
     * Verify PRoot binary is available in the native library directory.
     */
    suspend fun installProotIfNeeded() = withContext(Dispatchers.IO) {
        if (!prootBinary.exists() || !prootBinary.canExecute()) {
            throw IllegalStateException(
                "PRoot binary not found at $prootBinary. " +
                "It should be auto-extracted from jniLibs."
            )
        }

        // No libtalloc staging: deps/build_proot.sh links talloc statically
        // (the binary carries no DT_NEEDED for libtalloc.so), so there is no
        // shared object to version-rename. Older builds shipped libtalloc.so
        // in jniLibs and copied it here as libtalloc.so.2.

        Log.d(TAG, "PRoot binary available at $prootBinary")
    }

    /**
     * Reset rootfs. Optionally keeps user data (/root).
     * Returns the backup directory if keepUserData is true, null otherwise.
     */
    suspend fun reset(keepUserData: Boolean = false): File? = withContext(Dispatchers.IO) {
        var backupDir: File? = null

        // Hold aptMutex across dump → wipe → restore so a boot-time retry
        // cannot race a half-deleted rootfs. Mutex is non-reentrant, so the
        // locked dump/restore cores are used (not the public wrappers).
        aptMutex.withLock {
            runCatching { dumpDpkgWorldLocked() }
            runCatching { dumpPipWorldLocked() }

            if (keepUserData) {
                val rootHome = File(rootfsDir, "root")
                if (rootHome.exists()) {
                    backupDir = File(context.cacheDir, "rootfs-backup-root")
                    backupDir.deleteRecursively()
                    rootHome.copyRecursively(backupDir, overwrite = true)
                }
            }

            HostStatusPublisher.stop()
            rootfsDir.deleteRecursively()
            installIfNeeded()

            if (backupDir != null && backupDir.exists()) {
                val rootHome = File(rootfsDir, "root")
                backupDir.copyRecursively(rootHome, overwrite = true)
                backupDir.deleteRecursively()
            }

            runCatching { restoreDpkgWorldUnlocked() }
            runCatching { restorePipWorldUnlocked() }
        }

        backupDir
    }

    /**
     * Calculate the total size of the rootfs directory in bytes.
     */
    suspend fun getRootfsSize(): Long = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) return@withContext 0L
        calculateDirSize(rootfsDir)
    }

    /**
     * Restore user data from a backup directory into /root.
     */
    suspend fun restoreUserData(backupDir: File) = withContext(Dispatchers.IO) {
        if (!backupDir.exists()) return@withContext
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()
        backupDir.copyRecursively(rootHome, overwrite = true)
        backupDir.deleteRecursively()
        Log.i(TAG, "User data restored from $backupDir")
    }

    private fun calculateDirSize(dir: File): Long {
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            total += if (file.isDirectory) {
                calculateDirSize(file)
            } else {
                file.length()
            }
        }
        return total
    }

    /**
     * Ensure session-specific directories exist on the host filesystem.
     */
    fun ensureSessionDirs(sessionId: String) {
        SessionWorkspace.ensureDirs(context.filesDir, sessionId)
    }

    /**
     * Read system DNS servers and search domains from ConnectivityManager,
     * then write resolv.conf into the Alpine rootfs.
     * Mirrors iOS ISHKernel.configureDns / refreshDns.
     * Falls back to 8.8.8.8 / 8.8.4.4 if no system DNS available.
     */
    fun refreshDns() {
        if (!rootfsDir.exists()) return

        val resolvConf = StringBuilder()

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps: LinkProperties? = if (network != null) cm.getLinkProperties(network) else null

            if (linkProps != null) {
                // Search domains
                val domains = linkProps.domains
                if (!domains.isNullOrBlank()) {
                    resolvConf.append("search $domains\n")
                    Log.i(TAG, "[DNS] search domains: $domains")
                }

                // Nameservers
                val dnsServers = linkProps.dnsServers
                if (dnsServers.isNotEmpty()) {
                    for (server in dnsServers) {
                        val addr = server.hostAddress ?: continue
                        resolvConf.append("nameserver $addr\n")
                        Log.i(TAG, "[DNS] system server: $addr")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DNS] Failed to read system DNS: ${e.message}")
        }

        // Fallback to public DNS if no system servers were found
        if (!resolvConf.contains("nameserver")) {
            Log.i(TAG, "[DNS] no system DNS — using fallback: 8.8.8.8, 8.8.4.4")
            resolvConf.append("nameserver 8.8.8.8\n")
            resolvConf.append("nameserver 8.8.4.4\n")
        }

        try {
            val file = File(rootfsDir, "etc/resolv.conf")
            file.parentFile?.mkdirs()
            file.writeText(resolvConf.toString())
            Log.i(TAG, "[DNS] resolv.conf updated:\n$resolvConf")
        } catch (e: Exception) {
            Log.e(TAG, "[DNS] Failed to write resolv.conf: ${e.message}")
        }
    }

    /**
     * Copy every file under `assets/default_mount/` into the rootfs, preserving
     * the directory layout. Mirrors iOS RootfsManager.applyDefaultMountOverlay
     * (src/ios/iSH/RootfsManager.swift:153-225) — runs on every boot so shipping
     * updated profile scripts / URL wrappers with an app release just works.
     *
     * Files under `bin` / `sbin` paths get the execute bit set. Existing files
     * are overwritten so users see the latest shipped version even if they
     * previously edited the file — matching iOS behavior.
     *
     * Ubuntu-base ships a deb822 `ubuntu.sources` aimed at archive.ubuntu.com
     * (amd64). arm64 packages live on ports.ubuntu.com; disable the stock
     * file so overlay `etc/apt/sources.list` is the only source.
     */
    private fun configureUbuntuGuest() {
        if (!rootfsDir.exists()) return
        val deb822 = File(rootfsDir, "etc/apt/sources.list.d/ubuntu.sources")
        if (deb822.exists()) {
            val disabled = File(rootfsDir, "etc/apt/sources.list.d/ubuntu.sources.disabled")
            if (disabled.exists()) disabled.delete()
            if (!deb822.renameTo(disabled)) {
                deb822.delete()
            }
        }
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists() || hosts.length() < 8) {
            hosts.parentFile?.mkdirs()
            hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        // Guest TMPDIR=/tmp; ubuntu-base usually ships these, but a partial
        // extract or overlay must not leave dpkg without a writable temp.
        File(rootfsDir, "tmp").mkdirs()
        File(rootfsDir, "var/tmp").mkdirs()
        installBundledAndroidSdkTools()
        installBundledCmdlineTools()
    }

    /**
     * Write the Android system trust store into the guest so apt/curl/git/pip
     * have a CA bundle even when ubuntu-base hash-symlinks failed to extract
     * or `ca-certificates` has never been configured.
     *
     * Must run on the **host**. PRoot cannot see `/apex/.../cacerts` unless we
     * bind-mount it. Android 14+ moved CAs into the conscrypt APEX while
     * `/system/etc/security/cacerts/` is often an empty stub — scanning only
     * that path yields "no CA chain". [AndroidCAStore] is the public API that
     * still works when the APEX dir is SELinux-blocked. Filesystem dirs are a
     * fallback and also supply OpenSSL hash `.0` files (copied, never
     * symlinked at Android paths the guest cannot follow).
     */
    private fun injectHostCaBundle() {
        ensureCaHookExecutable()
        val androidPems = LinkedHashMap<String, String>()
        try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val cert = ks.getCertificate(aliases.nextElement()) as? X509Certificate ?: continue
                val fp = try {
                    CaBundle.fingerprintDer(cert.encoded)
                } catch (_: Exception) {
                    continue
                }
                if (fp in androidPems) continue
                val block = StringBuilder()
                appendPem(block, cert)
                androidPems[fp] = block.toString()
            }
            Log.i(TAG, "[CA] AndroidCAStore unique=${androidPems.size}")
        } catch (t: Throwable) {
            Log.w(TAG, "[CA] AndroidCAStore export failed: ${t.message}")
        }
        val guestCerts = File(rootfsDir, "etc/ssl/certs")
        guestCerts.mkdirs()
        var source = "AndroidCAStore"
        if (androidPems.size < 8) {
            for (dirPath in HOST_CA_DIRS) {
                val dir = File(dirPath)
                val extra = loadPemFromHostDir(dir) ?: continue
                var added = 0
                for (block in CaBundle.parsePemBlocks(extra)) {
                    val fp = CaBundle.fingerprintPem(block) ?: continue
                    val pemBlock = if (block.endsWith("\n")) block else "$block\n"
                    if (androidPems.putIfAbsent(fp, pemBlock) == null) added++
                }
                copyHostCaHashFiles(dir, guestCerts)
                source = dirPath
                if (added > 0) {
                    Log.i(TAG, "[CA] merged $added unique certs from $dirPath (total=${androidPems.size})")
                }
                if (androidPems.size >= 8) break
            }
        } else {
            for (dirPath in HOST_CA_DIRS) {
                if (copyHostCaHashFiles(File(dirPath), guestCerts) > 0) break
            }
        }
        if (androidPems.isEmpty()) {
            Log.w(TAG, "[CA] host bundle empty; leaving guest certs (HTTPS may fall back to HTTP)")
            return
        }

        val mozillaPems = CaBundle.loadPemsFromTree(
            File(rootfsDir, "usr/share/ca-certificates"),
            excludeDirNames = setOf("minis-android"),
        )
        val mozillaFp = HashSet<String>(mozillaPems.size)
        for (block in mozillaPems) {
            CaBundle.fingerprintPem(block)?.let { mozillaFp.add(it) }
        }
        val extraFps = if (mozillaFp.isEmpty()) {
            androidPems.keys
        } else {
            androidPems.keys.filter { it !in mozillaFp }
        }
        val localDir = File(rootfsDir, "usr/local/share/ca-certificates/minis-android")
        val shareDir = File(rootfsDir, "usr/share/ca-certificates/minis-android")
        for (dir in listOf(localDir, shareDir)) {
            try {
                dir.deleteRecursively()
            } catch (_: Exception) {
            }
            dir.mkdirs()
            chmodWorld(dir, true)
            dir.parentFile?.let { chmodWorld(it, true) }
        }
        var extrasWritten = 0
        val confNames = ArrayList<String>()
        for (fp in extraFps) {
            val pemBlock = androidPems[fp] ?: continue
            val name = "${fp.take(16)}.crt"
            try {
                val local = File(localDir, name)
                local.writeText(pemBlock)
                chmodWorld(local, false)
                val share = File(shareDir, name)
                share.writeText(pemBlock)
                chmodWorld(share, false)
                confNames += "minis-android/$name"
                extrasWritten++
            } catch (t: Throwable) {
                Log.w(TAG, "[CA] failed to write $name: ${t.message}")
            }
        }
        registerHostCaConf(confNames)
        Log.i(TAG, "[CA] registered $extrasWritten host extras from $source (mozilla=${mozillaFp.size})")

        val pem = StringBuilder()
        val seen = LinkedHashSet<String>()
        for (block in mozillaPems) {
            val fp = CaBundle.fingerprintPem(block) ?: continue
            if (!seen.add(fp)) continue
            pem.append(block)
            if (!block.endsWith("\n")) pem.append('\n')
        }
        for ((fp, block) in androidPems) {
            if (!seen.add(fp)) continue
            pem.append(block)
            if (!block.endsWith("\n")) pem.append('\n')
        }
        if (pem.length < 2048) {
            Log.w(TAG, "[CA] merged bundle too small (${pem.length}); leaving guest certs (HTTPS may fall back to HTTP)")
            return
        }
        val dest = File(guestCerts, "ca-certificates.crt")
        dest.writeText(pem.toString())
        chmodWorld(dest, false)
        ensureCaHookExecutable()
        val copies = listOf(
            File(rootfsDir, "usr/lib/ssl/cert.pem"),
            File(rootfsDir, "etc/ssl/cert.pem"),
        )
        for (copy in copies) {
            try {
                copy.parentFile?.mkdirs()
                dest.copyTo(copy, overwrite = true)
                chmodWorld(copy, false)
            } catch (t: Throwable) {
                Log.w(TAG, "[CA] failed to copy bundle to ${copy.name}: ${t.message}")
            }
        }
        val share = File(rootfsDir, "usr/local/share/ca-certificates")
        share.mkdirs()
        publishUnprivilegedCa(guestCerts, pem.toString())
        Log.i(TAG, "[CA] injected $source (${pem.length} bytes) → ${dest.absolutePath}")
    }

    /**
     * PRoot `-0` reports uid 0, but `access()` still honors the on-disk mode.
     * Android's umask is 0077, so a bundle written as 0600 is invisible to
     * `_apt`, `nobody`, and any `su` that dropped the process environment.
     * Make the tree 0755/0644, emit OpenSSL subject-hash links, and persist
     * `SSL_CERT_FILE` in files the guest reads without our envp.
     */
    private fun publishUnprivilegedCa(guestCerts: File, pem: String) {
        var dir: File? = guestCerts
        while (dir != null && dir != rootfsDir) {
            chmodWorld(dir, true)
            dir = dir.parentFile
        }
        writeOpenSslHashFiles(guestCerts, pem)
        guestCerts.listFiles()?.forEach { child -> chmodWorld(child, child.isDirectory) }
        val envBody = """
            SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
            SSL_CERT_DIR=/etc/ssl/certs
            CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
            REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt
            GIT_SSL_CAINFO=/etc/ssl/certs/ca-certificates.crt
            PIP_CERT=/etc/ssl/certs/ca-certificates.crt
            NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
        """.trimIndent() + "\n"
        val envFile = File(rootfsDir, "etc/environment")
        envFile.parentFile?.mkdirs()
        val kept = if (envFile.exists()) {
            envFile.readLines().filterNot { line ->
                line.substringBefore("=").trim() in CA_ENV_KEYS
            }
        } else {
            emptyList()
        }
        envFile.writeText((kept + envBody.trim().lines()).filter { it.isNotBlank() }.joinToString("\n") + "\n")
        chmodWorld(envFile, false)
        val snippet = """
            # minis-ca-env
            export SSL_CERT_FILE="${'$'}{SSL_CERT_FILE:-/etc/ssl/certs/ca-certificates.crt}"
            export SSL_CERT_DIR="${'$'}{SSL_CERT_DIR:-/etc/ssl/certs}"
            export CURL_CA_BUNDLE="${'$'}{CURL_CA_BUNDLE:-${'$'}SSL_CERT_FILE}"
            export REQUESTS_CA_BUNDLE="${'$'}{REQUESTS_CA_BUNDLE:-${'$'}SSL_CERT_FILE}"
            export GIT_SSL_CAINFO="${'$'}{GIT_SSL_CAINFO:-${'$'}SSL_CERT_FILE}"
            export PIP_CERT="${'$'}{PIP_CERT:-${'$'}SSL_CERT_FILE}"
            export NODE_EXTRA_CA_CERTS="${'$'}{NODE_EXTRA_CA_CERTS:-${'$'}SSL_CERT_FILE}"
        """.trimIndent() + "\n"
        for (rel in listOf("etc/bash.bashrc", "etc/profile", "root/.bashrc")) {
            val f = File(rootfsDir, rel)
            f.parentFile?.mkdirs()
            val existing = if (f.exists()) f.readText() else ""
            if (!existing.contains("# minis-ca-env")) {
                f.appendText(if (existing.endsWith("\n") || existing.isEmpty()) snippet else "\n$snippet")
            }
            chmodWorld(f, false)
        }
    }

    private fun writeOpenSslHashFiles(certsDir: File, pem: String) {
        val certs = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(pem.byteInputStream())
                .filterIsInstance<X509Certificate>()
        } catch (t: Throwable) {
            Log.w(TAG, "[CA] hash parse failed: ${t.message}")
            return
        }
        val used = HashMap<String, Int>()
        for (cert in certs) {
            val body = StringBuilder()
            appendPem(body, cert)
            val text = body.toString()
            for (hash in listOf(OpenSslSubjectHash.oldHash(cert), OpenSslSubjectHash.newHash(cert))) {
                val n = used.getOrDefault(hash, 0)
                used[hash] = n + 1
                val out = File(certsDir, "$hash.$n")
                try {
                    if (java.nio.file.Files.isSymbolicLink(out.toPath())) out.delete()
                    out.writeText(text)
                    chmodWorld(out, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "[CA] hash ${out.name} failed: ${t.message}")
                }
            }
        }
    }

    /**
     * Enable host-only certs in ca-certificates.conf so `update-ca-certificates`
     * keeps them. Skipped when the package has not installed the conf yet;
     * `/usr/local/share/ca-certificates` still covers that case.
     */
    private fun registerHostCaConf(relativePaths: List<String>) {
        val conf = File(rootfsDir, "etc/ca-certificates.conf")
        if (!conf.isFile) return
        try {
            conf.writeText(CaBundle.rewriteCaCertificatesConf(conf.readText(), relativePaths))
            chmodWorld(conf, false)
        } catch (t: Throwable) {
            Log.w(TAG, "[CA] conf register failed: ${t.message}")
        }
    }

    /** run-parts ignores a non-executable update.d hook. Os.chmod beats umask 0077. */
    private fun ensureCaHookExecutable() {
        for (rel in listOf(
            "etc/ca-certificates/update.d/minis-dedup",
            "usr/local/bin/minis-ca-dedup",
        )) {
            val f = File(rootfsDir, rel)
            if (!f.isFile) continue
            try {
                android.system.Os.chmod(f.absolutePath, 493)
            } catch (_: Throwable) {
                f.setExecutable(true, false)
                f.setReadable(true, false)
            }
        }
    }

    /** 0644 for files, 0755 for directories. Os.chmod survives Android umask 0077. */
    private fun chmodWorld(file: File, directory: Boolean) {
        val mode = if (directory) 493 else 420
        try {
            android.system.Os.chmod(file.absolutePath, mode)
        } catch (_: Throwable) {
            file.setReadable(true, false)
            file.setExecutable(directory, false)
            if (!directory) file.setWritable(true, true)
        }
    }

    private fun appendPem(pem: StringBuilder, cert: X509Certificate) {
        pem.append("-----BEGIN CERTIFICATE-----\n")
        pem.append(Base64.encodeToString(cert.encoded, Base64.DEFAULT).trim())
        pem.append("\n-----END CERTIFICATE-----\n")
    }

    /**
     * Read a host CA directory. Empty-but-present dirs (Android 14+
     * `/system/etc/security/cacerts`) must not count as a hit.
     */
    private fun loadPemFromHostDir(dir: File): String? {
        val files = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return null
        if (files.isEmpty()) return null
        val pem = StringBuilder()
        val cf = try {
            CertificateFactory.getInstance("X.509")
        } catch (_: Exception) {
            null
        }
        for (f in files) {
            if (!f.isFile || f.length() == 0L) continue
            val bytes = try {
                f.readBytes()
            } catch (_: Exception) {
                continue
            }
            val text = String(bytes, Charsets.ISO_8859_1)
            if (text.contains("BEGIN CERTIFICATE")) {
                pem.append(text)
                if (!text.endsWith("\n")) pem.append('\n')
            } else if (cf != null) {
                try {
                    val cert = cf.generateCertificate(bytes.inputStream()) as? X509Certificate ?: continue
                    appendPem(pem, cert)
                } catch (_: Exception) {
                }
            }
        }
        return if (pem.length >= 2048) pem.toString() else null
    }

    private fun copyHostCaHashFiles(dir: File, guestCerts: File): Int {
        val files = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return 0
        var n = 0
        for (f in files) {
            if (!f.isFile || f.length() == 0L) continue
            try {
                f.copyTo(File(guestCerts, f.name), overwrite = true)
                n++
            } catch (_: Exception) {
            }
        }
        if (n > 0) Log.i(TAG, "[CA] copied $n hash certs from ${dir.path}")
        return n
    }

    /**
     * Unpack vendored aarch64 aapt2/zipalign/adb into `/opt/android-sdk`.
     * Google's official build-tools are x86_64; these binaries are AOSP static
     * aarch64 builds (lzhiyong/android-sdk-tools 35.0.2).
     */
    private fun installBundledAndroidSdkTools() {
        val marker = File(rootfsDir, "opt/android-sdk/.minis-sdk-tools")
        val input = try {
            context.assets.open(SDK_TOOLS_ASSET)
        } catch (t: Throwable) {
            Log.w(TAG, "Bundled aarch64 SDK tools asset missing: ${t.message}")
            return
        }
        try {
            input.use { raw ->
                ZipInputStream(raw).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        if (name.isEmpty() || name.contains("..")) continue
                        val destRel = when {
                            name.startsWith("build-tools/") ->
                                "opt/android-sdk/build-tools/$SDK_BUILD_TOOLS_REV/" +
                                    name.removePrefix("build-tools/")
                            name.startsWith("platform-tools/") ->
                                "opt/android-sdk/$name"
                            else -> continue
                        }
                        val out = File(rootfsDir, destRel)
                        if (entry.isDirectory || name.endsWith("/")) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        out.setExecutable(true, false)
                    }
                }
            }
            File(rootfsDir, "opt/android-sdk/build-tools/$SDK_BUILD_TOOLS_REV/source.properties")
                .writeText("Pkg.UserSrc=false\nPkg.Revision=$SDK_BUILD_TOOLS_REV\n")
            File(rootfsDir, "opt/android-sdk/platform-tools/source.properties")
                .writeText("Pkg.UserSrc=false\nPkg.Revision=$SDK_BUILD_TOOLS_REV\n")
            val gradleProps = File(rootfsDir, "root/.gradle/gradle.properties")
            gradleProps.parentFile?.mkdirs()
            val override =
                "android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/$SDK_BUILD_TOOLS_REV/aapt2"
            val existing = if (gradleProps.exists()) gradleProps.readText() else ""
            val next = if (existing.contains("android.aapt2FromMavenOverride")) {
                existing.replace(Regex("""(?m)^android\.aapt2FromMavenOverride=.*$"""), override)
            } else if (existing.isEmpty() || existing.endsWith("\n")) {
                existing + "$override\n"
            } else {
                existing + "\n$override\n"
            }
            gradleProps.writeText(next)
            marker.parentFile?.mkdirs()
            marker.writeText("$SDK_BUILD_TOOLS_REV\n")
            Log.i(TAG, "Installed bundled aarch64 SDK tools $SDK_BUILD_TOOLS_REV")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to install bundled aarch64 SDK tools: ${t.message}", t)
        }
    }

    /**
     * Unpack the slimmed Google cmdline-tools (Java sdkmanager only) into
     * `/opt/android-sdk/cmdline-tools/latest`. Lint/R8/kotlin-compiler are
     * omitted so the APK stays small; platforms still download at runtime.
     */
    private fun installBundledCmdlineTools() {
        val input = try {
            context.assets.open(CMD_TOOLS_ASSET)
        } catch (t: Throwable) {
            Log.w(TAG, "Bundled sdkmanager asset missing: ${t.message}")
            return
        }
        try {
            input.use { raw ->
                ZipInputStream(raw).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        if (name.isEmpty() || name.contains("..")) continue
                        if (!name.startsWith("cmdline-tools/")) continue
                        val out = File(rootfsDir, "opt/android-sdk/$name")
                        if (entry.isDirectory || name.endsWith("/")) {
                            out.mkdirs()
                            continue
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        if (name.contains("/bin/")) out.setExecutable(true, false)
                    }
                }
            }
            Log.i(TAG, "Installed bundled Java sdkmanager")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to install bundled sdkmanager: ${t.message}", t)
        }
    }


    /** Drop the previous Alpine extract so it doesn't sit around after the distro switch. */
    private fun deleteLegacyAlpineRootfs() {
        val legacy = File(context.filesDir, "alpine-rootfs")
        if (legacy.exists()) {
            Log.i(TAG, "Deleting legacy Alpine rootfs at $legacy")
            legacy.deleteRecursively()
        }
    }

    /**
     * No-op when the asset dir is missing or the rootfs hasn't been extracted.
     */
    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) {
            Log.d(TAG, "[DefaultMount] rootfs missing, skipping overlay")
            return@withContext
        }

        // AssetManager.list() returns an empty array for both "leaf file" and
        // "missing path", so probe for children before recursing — otherwise
        // a missing overlay dir would be mistaken for a single leaf file.
        val rootEntries = try {
            context.assets.list(DEFAULT_MOUNT_ASSET)
        } catch (t: Throwable) {
            null
        }
        if (rootEntries.isNullOrEmpty()) {
            Log.d(TAG, "[DefaultMount] no default_mount assets found, skipping overlay")
            return@withContext
        }

        val startNs = System.nanoTime()
        var fileCount = 0
        try {
            fileCount = copyAssetDir(DEFAULT_MOUNT_ASSET, rootfsDir)
            ensureCaHookExecutable()
            configureUbuntuGuest()
            injectHostCaBundle()
        } catch (t: Throwable) {
            Log.w(TAG, "[DefaultMount] overlay failed: ${t.message}", t)
            return@withContext
        }

        // Mirror iOS removeExternallyManagedMarker() — drop PEP 668 marker so
        // `pip install` Just Works inside this embedded Alpine rootfs even
        // when the shipped pip.conf isn't being read (e.g. pip invoked with
        // --isolated or via a venv). Safe: this is a single-tenant sandbox.
        val markerRemoved = removeExternallyManagedMarker()

        // [T-mcp-cli-readonly-android] Make the shipped minis-mcp-cli Python lib
        // read-only inside the guest so a user can't `vi`-tamper the bundled
        // scripts (mirrors iOS #707). Scoped to /usr/local/lib/minis-mcp-cli/
        // ONLY — the wrapper at /usr/local/bin/minis-mcp-cli stays executable +
        // writable (app-managed). Re-applied on every boot AFTER the copy; the
        // copyAssetDir leaf-copy above re-opens read-only files writable first,
        // so the next app-upgrade overlay still overwrites cleanly.
        val lockedCount = lockMcpCliLibReadOnly()

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        Log.i(TAG, "[DefaultMount] Done. $fileCount file(s) overlaid, $markerRemoved EXTERNALLY-MANAGED marker(s) removed, $lockedCount minis-mcp-cli lib path(s) locked read-only in %.1fms".format(elapsedMs))
    }

    /**
     * [T-mcp-cli-readonly-android] Set the `/usr/local/lib/minis-mcp-cli/`
     * subtree read-only for the guest: directories 0555 (read+execute, no
     * write), files 0444 (read-only). Java's File API has no octal chmod, so
     * we use setWritable(false, false) + setReadable(true, false)
     * (+ setExecutable(true, false) on dirs) — the `false` ownerOnly arg makes
     * the permission apply to all users, matching the setReadable style used
     * for libtalloc above. Returns the number of paths adjusted; 0 if the lib
     * dir is absent. Idempotent — the pre-copy unlock in copyAssetDir keeps the
     * upgrade path working across boots.
     */
    private fun lockMcpCliLibReadOnly(): Int {
        val libDir = File(rootfsDir, "usr/local/lib/minis-mcp-cli")
        if (!libDir.isDirectory) return 0
        var count = 0
        // walkBottomUp so child files are locked before their parent dir loses
        // write — dir mode doesn't gate chmod of already-visited children, but
        // bottom-up keeps the intent clear and avoids any traversal surprise.
        for (f in libDir.walkBottomUp()) {
            f.setWritable(false, false)
            f.setReadable(true, false)
            if (f.isDirectory) f.setExecutable(true, false)
            count++
        }
        return count
    }

    /**
     * Remove the PEP 668 EXTERNALLY-MANAGED marker file from every
     * `usr/lib/python3*` directory in the rootfs. Mirrors iOS
     * RootfsManager.removeExternallyManagedMarker (src/ios/iSH/RootfsManager.swift:228-239).
     * Returns the number of markers removed.
     */
    private fun removeExternallyManagedMarker(): Int {
        val usrLib = File(rootfsDir, "usr/lib")
        val children = usrLib.listFiles() ?: return 0
        var removed = 0
        for (entry in children) {
            if (!entry.name.startsWith("python3")) continue
            val marker = File(entry, "EXTERNALLY-MANAGED")
            if (marker.exists() && marker.delete()) {
                Log.i(TAG, "[DefaultMount] Removed EXTERNALLY-MANAGED from ${entry.name}")
                removed++
            }
        }
        return removed
    }

    /**
     * Recursively copy an assets path into [targetBase]. Returns the number of
     * regular files written. Files under /bin/, /sbin/, /usr/bin/, /usr/sbin/,
     * /usr/local/bin/, /usr/local/sbin/ get the execute bit set.
     */
    private fun copyAssetDir(assetPath: String, targetBase: File, prefix: String = ""): Int {
        val entries = context.assets.list(assetPath) ?: return 0
        if (entries.isEmpty()) {
            // Leaf: asset is a file. Copy it.
            // User-chosen apt/pip/npm mirrors are restored after overlay, but
            // skip clobbering them when a .bak from applyMirror already exists
            // so a failed restore cannot briefly revert to official HTTP.
            if (prefix in OVERLAY_SKIP_IF_BAK && File(targetBase, "$prefix.bak").exists()) {
                Log.i(TAG, "[DefaultMount] skip overlay of $prefix (user mirror bak present)")
                return 0
            }
            val dest = File(targetBase, prefix)
            dest.parentFile?.mkdirs()
            // [T-mcp-cli-readonly-android] A prior boot may have set this file
            // (and its dir) read-only — the minis-mcp-cli lib subtree, locked
            // below. Re-open both writable before overwriting, otherwise an app
            // upgrade can't replace the shipped file: truncating an existing
            // file needs write on the FILE, and creating a new one needs write
            // on the PARENT DIR. No-op when already writable / not yet present.
            dest.parentFile?.setWritable(true, true)
            if (dest.exists()) dest.setWritable(true, true)
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            if (isUnderBinDir(prefix)) {
                dest.setExecutable(true, false)
            }
            return 1
        }
        var count = 0
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val childPrefix = if (prefix.isEmpty()) name else "$prefix/$name"
            count += copyAssetDir(childAsset, targetBase, childPrefix)
        }
        return count
    }

    private fun isUnderBinDir(relativePath: String): Boolean {
        val normalized = "/$relativePath"
        return BIN_DIR_PREFIXES.any { normalized.startsWith(it) }
    }

    // --- POSIX tar extraction ---

    /**
     * Extract a POSIX tar stream into the target directory.
     * Handles regular files, directories, and symlinks.
     * Does not depend on any external library.
     */
    internal fun extractTar(input: InputStream, targetDir: File) {
        val header = ByteArray(512)

        while (true) {
            val bytesRead = readFully(input, header)
            if (bytesRead < 512) break

            // Check for end-of-archive (two consecutive zero blocks)
            if (header.all { it == 0.toByte() }) break

            val name = extractString(header, 0, 100)
            val modeOctal = extractString(header, 100, 8)
            val sizeOctal = extractString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()
            val linkName = extractString(header, 157, 100)
            val mode = modeOctal.trim().toIntOrNull(8) ?: 0

            // Handle GNU/POSIX long names via prefix field (bytes 345-500)
            val prefix = extractString(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            if (fullName.isEmpty()) break

            val size = sizeOctal.trim().toLongOrNull(8) ?: 0L
            val outFile = File(targetDir, fullName)

            when (typeFlag) {
                '5', 'D' -> {
                    // Directory
                    outFile.mkdirs()
                }
                '2' -> {
                    // Symbolic link. Android app-private storage often rejects
                    // createSymbolicLink; fall back to copying the referent so
                    // /etc/ssl/certs hash names still resolve (otherwise apt/curl
                    // report "no valid CA chain").
                    outFile.parentFile?.mkdirs()
                    if (outFile.exists()) outFile.delete()
                    val linkPath = Paths.get(linkName)
                    try {
                        Files.createSymbolicLink(outFile.toPath(), linkPath)
                    } catch (_: Exception) {
                        val dest = if (linkPath.isAbsolute) {
                            File(targetDir, linkPath.toString().trimStart('/'))
                        } else {
                            File(outFile.parentFile, linkName)
                        }
                        try {
                            if (dest.exists() && dest.isFile) {
                                dest.copyTo(outFile, overwrite = true)
                            } else {
                                Log.w(TAG, "Failed to create symlink: $fullName -> $linkName")
                            }
                        } catch (t: Exception) {
                            Log.w(TAG, "Failed to materialize symlink $fullName: ${t.message}")
                        }
                    }
                }
                '0', '\u0000' -> {
                    // Regular file (type '0' or null/legacy)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // Preserve executable permission from tar header
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    // Skip padding to next 512-byte boundary
                    val remainder = (size % 512).toInt()
                    if (remainder != 0) {
                        skipFully(input, (512 - remainder).toLong())
                    }
                    continue // Already consumed data + padding
                }
                '1' -> {
                    // Hard link — create a copy
                    outFile.parentFile?.mkdirs()
                    val linkTarget = File(targetDir, linkName)
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }
                else -> {
                    // Unknown type, skip data
                }
            }

            // Skip data blocks for non-file entries that we didn't consume above
            if (typeFlag != '0' && typeFlag != '\u0000' && size > 0) {
                val blocks = (size + 511) / 512 * 512
                skipFully(input, blocks)
            }
        }
    }

    internal fun extractString(header: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, header.size)
        var actualEnd = offset
        for (i in offset until end) {
            if (header[i] == 0.toByte()) break
            actualEnd = i + 1
        }
        return String(header, offset, actualEnd - offset, Charset.forName("UTF-8"))
    }

    internal fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    internal fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
    }

    /**
     * InputStream wrapper that reports cumulative bytes read as a 0..1 fraction.
     * Throttles updates so each +1% bump emits at most once. Designed to wrap
     * the asset stream (not the gzip stream) so progress is monotonic against
     * a size we can cheaply know up front.
     */
    private class ProgressInputStream(
        inner: InputStream,
        private val total: Long,
        private val onProgress: (Float) -> Unit,
    ) : FilterInputStream(inner) {
        private var read: Long = 0
        private var lastReportedPercent: Int = -1

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { read += 1; publish() }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { read += n; publish() }
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) { read += skipped; publish() }
            return skipped
        }

        private fun publish() {
            if (total <= 0) return
            val pct = ((read * 100) / total).toInt().coerceIn(0, 99)
            if (pct != lastReportedPercent) {
                lastReportedPercent = pct
                onProgress(pct / 100f)
            }
        }
    }

    private val aptMutex get() = SandboxResourceGate.aptMutex
    private val pipWorldFile: File get() = File(context.filesDir, "pip-world.txt")
    private val pipWorldFailedFile: File get() = File(context.filesDir, "pip-world-failed.txt")
    private val dpkgWorldFile: File get() = File(context.filesDir, "dpkg-world.txt")
    private val dpkgWorldFailedFile: File get() = File(context.filesDir, "dpkg-world-failed.txt")

    /**
     * Point guest `/etc/localtime` at the host timezone so `date` / Python
     * datetime match the phone. The symlink target MUST stay a relative
     * Path (`Paths.get(want)`). `File(want).toPath()` resolves against the
     * process cwd and becomes an absolute host path, which PRoot cannot
     * follow inside the guest.
     */
    suspend fun applyHostTimezone() = withContext(Dispatchers.IO) {
        if (!isInstalled) return@withContext
        val zoneId = java.util.TimeZone.getDefault().toZoneId().id
        try {
            val localtime = File(rootfsDir, "etc/localtime")
            val target = File(rootfsDir, "usr/share/zoneinfo/$zoneId")
            if (!target.exists()) {
                Log.w(TAG, "[TzSync] zoneinfo missing for '$zoneId' — keeping UTC")
                return@withContext
            }
            val want = "../usr/share/zoneinfo/$zoneId"
            val current = runCatching {
                Files.readSymbolicLink(localtime.toPath()).toString()
            }.getOrNull()
            val tzFile = File(rootfsDir, "etc/timezone")
            val tzCurrent = runCatching { tzFile.readText().trim() }.getOrNull()
            if (current == want && tzCurrent == zoneId) return@withContext
            localtime.delete()
            Files.createSymbolicLink(localtime.toPath(), Paths.get(want))
            tzFile.writeText("$zoneId\n")
            Log.i(TAG, "[TzSync] /etc/localtime -> $want (device zone $zoneId)")
        } catch (t: Throwable) {
            Log.w(TAG, "[TzSync] failed to align /etc/localtime with $zoneId: ${t.message}")
        }
    }

    suspend fun dumpPipWorld() = aptMutex.withLock { dumpPipWorldLocked() }

    suspend fun restorePipWorld() = aptMutex.withLock { restorePipWorldUnlocked() }

    suspend fun retryFailedPipWorld() = aptMutex.withLock { retryFailedPipWorldUnlocked() }

    suspend fun dumpDpkgWorld() = aptMutex.withLock { dumpDpkgWorldLocked() }

    suspend fun restoreDpkgWorld() = aptMutex.withLock { restoreDpkgWorldUnlocked() }

    suspend fun retryFailedDpkgWorld() = aptMutex.withLock { retryFailedDpkgWorldUnlocked() }

    /** Guest-side HTTP probe + rewrite of /etc/apt/sources.list. Fire-and-forget from boot. */
    suspend fun runMinisMirrorAuto() = withContext(Dispatchers.IO) {
        aptMutex.withLock { runMinisMirrorAutoLocked() }
    }

    /**
     * First-boot / heal path: install curl/wget/python3/git (and nodejs once)
     * so agent tools work without waiting for the full minis-dev-setup toolchain.
     */
    suspend fun seedNetworkTools() = withContext(Dispatchers.IO) {
        aptMutex.withLock { seedNetworkToolsLocked() }
    }

    private fun runMinisMirrorAutoLocked() {
        val helper = File(rootfsDir, "usr/local/bin/minis-mirror")
        if (!helper.exists()) return
        val cmd = listOf(
            prootBinary.absolutePath, "-0", "--link2symlink", "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-w", "/root",
            "/usr/local/bin/minis-mirror", "auto",
        )
        val r = runProotWithDeadline(cmd, prootLoaderEnv(), 180)
        Log.i(TAG, "[minis-mirror auto] exit=${r.exitCode}")
    }

    private fun seedNetworkToolsLocked() {
        if (!prootBinary.exists()) return
        
        // Only seed truly essential packages that are missing
        val essentials = mutableListOf<String>()
        val ca = File(rootfsDir, "etc/ssl/certs/ca-certificates.crt")
        if (!ca.exists() || ca.length() < 1024L) essentials += "ca-certificates"
        if (!File(rootfsDir, "usr/bin/curl").exists()) essentials += "curl"
        if (!File(rootfsDir, "usr/bin/wget").exists()) essentials += "wget"
        if (!File(rootfsDir, "usr/bin/python3").exists()) essentials += "python3"
        if (!File(rootfsDir, "usr/bin/git").exists()) essentials += "git"
        if (!File(rootfsDir, "usr/bin/fuser").exists()) essentials += "psmisc"
        if (!File(rootfsDir, "usr/bin/unzip").exists()) essentials += "unzip"
        
        val nodeBin = File(rootfsDir, "usr/bin/node")
        val nodejsBin = File(rootfsDir, "usr/bin/nodejs")
        val nodeAttempted = File(rootfsDir, "var/lib/minis/node-seed.attempted")
        val retryNode = RootfsUpgradePolicy.shouldRetryNodeSeed(
            nodeBin.exists() || nodejsBin.exists(),
            nodeAttempted.exists(),
        )
        if (essentials.isEmpty() && !retryNode) {
            Log.i(TAG, "[net-seed] all essentials present, skipping")
            return
        }
        if (essentials.isNotEmpty()) {
            Log.i(TAG, "[net-seed] installing ${essentials.joinToString()}")
            val r = runAptInstallInGuest(essentials)
            Log.i(TAG, "[net-seed] essentials exit=${r.exitCode}")
        }
        
        // Node.js: try once, but don't block boot. A previous launch may have
        // installed the other essentials and then lost the node attempt to a
        // busy lock; that must still retry here.
        if (retryNode) {
            Log.i(TAG, "[net-seed] attempting nodejs npm (best-effort)")
            val nr = runAptInstallInGuest(listOf("nodejs", "npm"))
            val present = nodeBin.exists() || nodejsBin.exists()
            Log.i(TAG, "[net-seed] nodejs exit=${nr.exitCode} present=$present")
            // Stamp only after the attempt finishes. Writing the marker first
            // made a killed process or a transient apt failure permanent:
            // later launches saw the marker and never retried, so an upgrade
            // that hit a busy apt lock never got node.
            if (RootfsUpgradePolicy.shouldStampNodeSeed(nr.exitCode, nr.output, present)) {
                nodeAttempted.parentFile?.mkdirs()
                nodeAttempted.writeText("1\n")
            } else {
                Log.w(TAG, "[net-seed] nodejs not installed; will retry next launch")
            }
        }
    }

    private fun dumpDpkgWorldLocked() {
        if (!prootBinary.exists()) return
        val status = File(rootfsDir, "var/lib/dpkg/status")
        if (!status.exists()) {
            Log.d(TAG, "[dpkg-world] dpkg db missing — skip dump")
            return
        }
        val manual = runAptMarkShowManualUnlocked()
        if (manual == null) {
            Log.w(TAG, "[dpkg-world] apt-mark showmanual failed — keeping previous snapshot")
            return
        }
        val names = manual.sorted()
        dpkgWorldFile.writeText(formatDpkgWorld(names))
        Log.i(TAG, "[dpkg-world] dumped ${names.size} manual package(s)")
    }

    private fun restoreDpkgWorldUnlocked() {
        if (!dpkgWorldFile.exists()) return
        val names = parseDpkgWorld(dpkgWorldFile.readText())
        if (names.isEmpty()) return
        val already = installedPackageNames()
        val missing = names.filter { it !in already }
        if (missing.isEmpty()) {
            dpkgWorldFailedFile.delete()
            Log.i(TAG, "[dpkg-world] restore skip — ${names.size} already present")
            return
        }
        val result = runAptInstallInGuest(missing)
        if (result.exitCode == 0) {
            dpkgWorldFailedFile.delete()
            Log.i(TAG, "[dpkg-world] restored ${missing.size} package(s)")
            return
        }
        val failed = extractFailedPackages(result.output, missing)
        dpkgWorldFailedFile.writeText(failed.joinToString("\n") + "\n")
        Log.w(TAG, "[dpkg-world] restore partial/fail exit=${result.exitCode}, queued ${failed.size}")
    }

    private fun installedPackageNames(): Set<String> {
        val status = File(rootfsDir, "var/lib/dpkg/status")
        if (!status.exists()) return emptySet()
        val out = linkedSetOf<String>()
        var pkg: String? = null
        var installed = false
        fun flush() {
            val p = pkg
            if (p != null && installed && DPKG_PKG_NAME.matches(p)) out += p
            pkg = null
            installed = false
        }
        for (raw in status.readText().lineSequence()) {
            if (raw.isBlank()) {
                flush()
                continue
            }
            when {
                raw.startsWith("Package: ") -> pkg = raw.removePrefix("Package: ").trim()
                raw.startsWith("Status: ") -> {
                    val parts = raw.removePrefix("Status: ").trim().split(Regex("\\s+"))
                    installed = parts.getOrNull(2) == "installed"
                }
            }
        }
        flush()
        return out
    }

    private fun retryFailedDpkgWorldUnlocked() {
        if (!dpkgWorldFailedFile.exists()) return
        val names = parseDpkgWorld(dpkgWorldFailedFile.readText())
        if (names.isEmpty()) {
            dpkgWorldFailedFile.delete()
            return
        }
        val stillMissing = names.filter { it !in installedPackageNames() }
        if (stillMissing.isEmpty()) {
            dpkgWorldFailedFile.delete()
            context.getSharedPreferences("dpkg_world_retry", Context.MODE_PRIVATE)
                .edit().putInt("strikes", 0).apply()
            return
        }
        // Heal mirrors before spending a retry strike on a dead source.
        runCatching { runMinisMirrorAutoLocked() }
            .onFailure { Log.w(TAG, "[dpkg-world] minis-mirror auto failed (non-fatal): ${it.message}") }

        val prefs = context.getSharedPreferences("dpkg_world_retry", Context.MODE_PRIVATE)
        val strikes = prefs.getInt("strikes", 0)
        if (strikes >= MAX_DPKG_WORLD_RETRY_STRIKES) {
            Log.w(TAG, "[dpkg-world] giving up after $strikes strikes")
            dpkgWorldFailedFile.delete()
            prefs.edit().remove("strikes").apply()
            return
        }
        val result = runAptInstallInGuest(stillMissing)
        if (result.exitCode == 0) {
            dpkgWorldFailedFile.delete()
            prefs.edit().putInt("strikes", 0).apply()
            Log.i(TAG, "[dpkg-world] retry restored ${stillMissing.size} package(s)")
        } else {
            val failed = extractFailedPackages(result.output, stillMissing)
            dpkgWorldFailedFile.writeText(failed.joinToString("\n") + "\n")
            prefs.edit().putInt("strikes", strikes + 1).apply()
            Log.w(TAG, "[dpkg-world] retry failed strike=${strikes + 1} queued=${failed.size}")
        }
    }

    private fun runAptMarkShowManualUnlocked(): List<String>? {
        val cmd = listOf(
            prootBinary.absolutePath, "-0", "--link2symlink", "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-w", "/root",
            "/usr/bin/apt-mark", "showmanual",
        )
        val r = runProotWithDeadline(cmd, prootLoaderEnv(), 120)
        if (r.exitCode != 0) {
            Log.w(TAG, "[dpkg-world] apt-mark showmanual exit=${r.exitCode}")
            return null
        }
        return parseDpkgWorld(r.output)
    }

    /**
     * `--no-upgrade` installs missing names without bumping packages that
     * already exist in the factory rootfs, so a full `apt-mark showmanual`
     * snapshot is safe to restore (no hardcoded factory package list).
     */
    private fun runAptInstallInGuest(pkgNames: Collection<String>): AptResult {
        if (pkgNames.isEmpty()) return AptResult(0, "")
        if (!prootBinary.exists()) return AptResult(-1, "")
        val pkgs = pkgNames.filter { DPKG_PKG_NAME.matches(it) }
        if (pkgs.isEmpty()) return AptResult(0, "")
        val script = GuestAptScript.install(pkgs)
        val cmd = listOf(
            prootBinary.absolutePath, "-0", "--link2symlink", "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root",
            "/bin/sh", "-c", script,
        )
        val r = runProotWithDeadline(cmd, prootLoaderEnv(), 600)
        Log.i(TAG, "[dpkg-world] apt install exit=${r.exitCode} pkgs=${pkgs.size}")
        return r
    }

    private fun extractFailedPackages(output: String, requested: Collection<String>): List<String> {
        val requestedSet = requested.toSet()
        val failed = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""Unable to locate package (\S+)"""),
            Regex("""Package '([^']+)' has no installation candidate"""),
            Regex("""Version '[^']*' for '([^']*)' was not found"""),
            Regex("""'?([A-Za-z0-9+.:-]+)'? (?:is not|but it is not) (?:installable|going to be installed)"""),
            Regex("""Depends: (\S+) but it is not (?:installable|going to be installed)"""),
        )
        for (line in output.lineSequence()) {
            for (re in patterns) {
                re.find(line)?.groupValues?.getOrNull(1)?.let { pkg ->
                    if (pkg.isNotEmpty()) failed += pkg
                }
            }
        }
        val known = failed.filter { it in requestedSet }
        return if (known.isEmpty()) requested.toList() else known
    }

    private fun parseDpkgWorld(text: String): List<String> {
        val out = linkedSetOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val name = line.substringBefore('=').trim()
            if (DPKG_PKG_NAME.matches(name)) out += name
        }
        return out.toList()
    }

    private fun formatDpkgWorld(names: Collection<String>): String {
        val body = names.joinToString("\n")
        return DPKG_WORLD_HEADER + body + if (body.isEmpty()) "" else "\n"
    }

    private fun dumpPipWorldLocked() {
        if (!prootBinary.exists()) return
        if (!File(rootfsDir, "usr/bin/python3").exists()) return
        val leaves = runPipLeavesUnlocked() ?: return
        val extras = leaves.filter { it.lowercase() !in FACTORY_PIP_BASELINE }.sorted()
        pipWorldFile.writeText(PIP_WORLD_HEADER + extras.joinToString("\n") + if (extras.isEmpty()) "" else "\n")
        Log.i(TAG, "[pip-world] dumped ${extras.size} packages")
    }

    private fun runPipLeavesUnlocked(): Set<String>? {
        val loaderEnv = prootLoaderEnv()
        val cmd = listOf(
            prootBinary.absolutePath, "-0", "--link2symlink", "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-w", "/root",
            "/usr/bin/python3", "-m", "pip", "list", "--not-required",
            "--format=freeze", "--disable-pip-version-check",
        )
        val r = runProotWithDeadline(cmd, loaderEnv, 120)
        if (r.exitCode != 0) {
            Log.w(TAG, "[pip-world] pip list failed exit=${r.exitCode}")
            return null
        }
        return parsePipWorld(r.output)
    }

    private fun restorePipWorldUnlocked() {
        if (!pipWorldFile.exists()) return
        val names = parsePipWorld(pipWorldFile.readText())
            .filter { it.lowercase() !in FACTORY_PIP_BASELINE }
        if (names.isEmpty()) return
        if (!runPipInstallUnlocked(names)) {
            pipWorldFailedFile.writeText(names.joinToString("\n") + "\n")
            Log.w(TAG, "[pip-world] restore failed, queued ${names.size} for retry")
        } else {
            pipWorldFailedFile.delete()
            Log.i(TAG, "[pip-world] restored ${names.size} packages")
        }
    }

    private fun retryFailedPipWorldUnlocked() {
        if (!pipWorldFailedFile.exists()) return
        val names = parsePipWorld(pipWorldFailedFile.readText())
            .filter { it.lowercase() !in FACTORY_PIP_BASELINE }
        if (names.isEmpty()) {
            pipWorldFailedFile.delete()
            return
        }
        val prefs = context.getSharedPreferences("pip_world_retry", android.content.Context.MODE_PRIVATE)
        val strikes = prefs.getInt("strikes", 0)
        if (strikes >= MAX_PIP_WORLD_RETRY_STRIKES) {
            Log.w(TAG, "[pip-world] giving up after $strikes strikes")
            pipWorldFailedFile.delete()
            return
        }
        if (runPipInstallUnlocked(names)) {
            pipWorldFailedFile.delete()
            prefs.edit().putInt("strikes", 0).apply()
            Log.i(TAG, "[pip-world] retry restored ${names.size} packages")
        } else {
            prefs.edit().putInt("strikes", strikes + 1).apply()
            Log.w(TAG, "[pip-world] retry failed strike=${strikes + 1}")
        }
    }

    private fun runPipInstallUnlocked(names: Collection<String>): Boolean {
        if (names.isEmpty()) return true
        if (!File(rootfsDir, "usr/bin/python3").exists()) return false
        val tmp = File(rootfsDir, "tmp")
        tmp.mkdirs()
        val req = File(tmp, "pip-world-requirements.txt")
        req.writeText(names.joinToString("\n") + "\n")
        val loaderEnv = prootLoaderEnv()
        val cmd = listOf(
            prootBinary.absolutePath, "-0", "--link2symlink", "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-w", "/root",
            "/usr/bin/python3", "-m", "pip", "install",
            "--ignore-installed", "--disable-pip-version-check", "--no-input",
            "-r", "/tmp/pip-world-requirements.txt",
        )
        val r = runProotWithDeadline(cmd, loaderEnv, 900)
        req.delete()
        return r.exitCode == 0
    }

    private fun prootLoaderEnv(): Map<String, String> {
        val env = mutableMapOf(
            "PATH" to UBUNTU_GUEST_PATH,
            "PROOT_TMP_DIR" to PRootKernel.getProotTmpDir(context).absolutePath,
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
            "TMPDIR" to "/tmp",
            "TMP" to "/tmp",
            "TEMP" to "/tmp",
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
            "DEBIAN_FRONTEND" to "noninteractive",
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "SSL_CERT_DIR" to "/etc/ssl/certs",
            "CURL_CA_BUNDLE" to "/etc/ssl/certs/ca-certificates.crt",
            "REQUESTS_CA_BUNDLE" to "/etc/ssl/certs/ca-certificates.crt",
            "GIT_SSL_CAINFO" to "/etc/ssl/certs/ca-certificates.crt",
            "PIP_CERT" to "/etc/ssl/certs/ca-certificates.crt",
            "NODE_EXTRA_CA_CERTS" to "/etc/ssl/certs/ca-certificates.crt",
        )
        File(nativeLibDir, "libproot-loader.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER"] = it.absolutePath
        }
        File(nativeLibDir, "libproot-loader32.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER_32"] = it.absolutePath
        }
        return env
    }

    private data class AptResult(val exitCode: Int, val output: String)

    private fun runProotWithDeadline(
        cmd: List<String>,
        loaderEnv: Map<String, String>,
        timeoutSec: Long,
    ): AptResult {
        val p = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply { environment().putAll(loaderEnv) }
            .start()
        val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            try {
                p.inputStream.readBytes().toString(Charset.forName("UTF-8"))
            } catch (_: Exception) {
                ""
            }
        }
        val finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        val code: Int
        if (finished) {
            code = p.exitValue()
        } else {
            p.destroyForcibly()
            code = -1
            Log.w(TAG, "[Proot] child timed out after ${timeoutSec}s")
        }
        val output = try {
            outputFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            outputFuture.cancel(true)
            ""
        }
        return AptResult(code, output)
    }

    private fun parsePipWorld(text: String): Set<String> {
        val out = linkedSetOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue
            val name = line.substringBefore("==").substringBefore("=").trim()
            if (name.matches(Regex("[A-Za-z0-9._-]+"))) out += name
        }
        return out
    }

    companion object {
        private const val TAG = "RootfsManager"
        private val CA_ENV_KEYS = setOf(
            "SSL_CERT_FILE",
            "SSL_CERT_DIR",
            "CURL_CA_BUNDLE",
            "REQUESTS_CA_BUNDLE",
            "GIT_SSL_CAINFO",
            "PIP_CERT",
            "NODE_EXTRA_CA_CERTS",
        )
        private const val UBUNTU_GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin:" +
                "/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:" +
                "/opt/android-sdk/build-tools/35.0.2:/opt/android-sdk/cmake/3.22.1/bin:/opt/gradle/bin"
        private val FACTORY_PIP_BASELINE = setOf("pip", "setuptools", "wheel")
        private const val PIP_WORLD_HEADER = "# pip-world snapshot — extra packages beyond factory\n"
        private const val DPKG_WORLD_HEADER = "# dpkg-world snapshot — apt-mark showmanual names, one per line\n"
        private val DPKG_PKG_NAME = Regex("[A-Za-z0-9][A-Za-z0-9+.:_-]*")
        private const val MAX_PIP_WORLD_RETRY_STRIKES = 3
        private const val MAX_DPKG_WORLD_RETRY_STRIKES = 3
        private const val ARCH = "aarch64"
        private const val ROOTFS_ASSET = "ubuntu-base.tar.gz"
        private const val ROOTFS_ASSET_TAR = "ubuntu-base.tar"
        private const val DISTRO = "ubuntu-noble"
        private const val PROOT_ASSET = "proot-aarch64"
        private const val DEFAULT_MOUNT_ASSET = "default_mount"
        private const val SDK_TOOLS_ASSET = "android-sdk-tools-aarch64.zip"
        private const val CMD_TOOLS_ASSET = "android-cmdline-tools.zip"
        private const val SDK_BUILD_TOOLS_REV = "35.0.2"

        /**
         * Android 14+ stores CAs in the conscrypt APEX; `/system/etc/security/cacerts`
         * is often present but empty. Scan in this order; skip empty dirs.
         */
        private val HOST_CA_DIRS = listOf(
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
            "/data/misc/user/0/cacerts-added",
            "/system/etc/security/cacerts_original",
        )

        /** Overlay leaves these alone when applyMirror already wrote a .bak. */
        private val OVERLAY_SKIP_IF_BAK = setOf(
            "etc/apt/sources.list",
            "etc/pip.conf",
            "root/.npmrc",
        )

        /**
         * Rootfs paths whose contents must be executable. Matches iOS
         * RootfsManager.swift:199 byte-for-byte so the same overlay tree
         * produces identical file modes on both platforms.
         */
        private val BIN_DIR_PREFIXES = listOf(
            "/bin/",
            "/sbin/",
            "/usr/bin/",
            "/usr/sbin/",
            "/usr/local/bin/",
            "/usr/local/sbin/",
        )

        @Volatile
        private var instance: RootfsManager? = null

        fun getInstance(context: Context): RootfsManager {
            return instance ?: synchronized(this) {
                instance ?: RootfsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

/**
 * Upgrade decisions that must not depend on Android so a unit test can pin them.
 * A missing distro marker on a real guest tree is a pre-marker Ubuntu install,
 * not a partial extract. A node seed marker written before apt returns turns a
 * one-shot network/lock failure into a permanent missing runtime.
 */
internal object RootfsUpgradePolicy {
    fun shouldAdoptMissingDistroMarker(archMatches: Boolean, guestTreePresent: Boolean): Boolean =
        archMatches && guestTreePresent

    fun shouldStampNodeSeed(exitCode: Int, output: String, binaryPresent: Boolean): Boolean {
        if (binaryPresent || exitCode == 0) return true
        return output.contains("Unable to locate package") ||
            output.contains("has no installation candidate")
    }

    /**
     * 1.36.23: a lock or network failure must be retried next boot. Skipping the
     * whole seed because curl/git are already installed also skipped that retry.
     */
    fun shouldRetryNodeSeed(nodeBinaryPresent: Boolean, stampExists: Boolean): Boolean =
        !nodeBinaryPresent && !stampExists
}
