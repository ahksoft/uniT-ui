package ahk.unitool.gsm.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * Installs UniTool's private terminal layout and exposes ABI-specific native
 * tools from ApplicationInfo.nativeLibraryDir as normal shell commands.
 *
 * The executable payloads are supplied by jniLibs. This class deliberately
 * does not extract or download executable code from an APK at runtime.
 */
internal object NativeToolchain {
    private const val TAG = "UniToolToolchain"
    private const val BOOTSTRAP_ASSET = "bootstrap"
    private const val LINK_MAP_ASSET = "lib_symlinks.properties"
    private const val PYTHON_RUNTIME_ASSET = "termux-python-runtime.zip"
    private const val BOOTSTRAP_VERSION = "14"

    data class Environment(
        val prefix: String,
        val home: String,
        val bin: String,
        val lib: String,
        val tmp: String,
        val adb: String,
        val libusb: String,
        val shell: String,
        val availableCommands: Set<String>,
        val missingLibraries: Set<String>
    )

    @Synchronized
    fun install(context: Context): Environment {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val prefix = File(context.filesDir, "usr")
        val binDir = File(prefix, "bin")
        val libDir = File(prefix, "lib")
        val etcDir = File(prefix, "etc")
        val tmpDir = File(prefix, "tmp")
        val homeDir = File(context.filesDir, "home")

        listOf(prefix, binDir, libDir, etcDir, tmpDir, homeDir).forEach {
            if (!it.exists() && !it.mkdirs()) {
                Log.w(TAG, "Could not create directory: ${it.absolutePath}")
            }
        }

        installBootstrap(context, prefix, homeDir)
        val available = installCommandLinks(context, nativeDir, prefix).toMutableSet()
        listOf("git", "curl", "wget", "openssl", "less", "python", "python3", "pip", "pip3", "pip3.14", "pkg", "apt", "apt-get", "apt-cache", "dpkg", "dpkg-deb", "dpkg-query").forEach { command ->
            if (File(binDir, command).canExecute()) available.add(command)
        }
        val expectedLibraries = readLibraryMap(context).stringPropertyNames()
        val missing = expectedLibraries.filterNot { File(nativeDir, it).isFile }.toSet()

        val adb = File(binDir, "adb").absolutePath
        val shellLink = File(binDir, "sh")
        val shell = if (shellLink.isFile || isSymlink(shellLink)) {
            shellLink.absolutePath
        } else {
            "/system/bin/sh"
        }

        return Environment(
            prefix = prefix.absolutePath,
            home = homeDir.absolutePath,
            bin = binDir.absolutePath,
            lib = "${libDir.absolutePath}:${nativeDir.absolutePath}",
            tmp = tmpDir.absolutePath,
            adb = adb,
            libusb = File(nativeDir, "libusb.so").absolutePath,
            shell = shell,
            availableCommands = available,
            missingLibraries = missing
        )
    }

    private fun installBootstrap(context: Context, prefix: File, home: File) {
        val marker = File(prefix, ".unitool-bootstrap-version")
        if (marker.isFile && marker.readText().trim() == BOOTSTRAP_VERSION) return

        try {
            // Remove aliases left by the previous libpython_launcher.so-based
            // runtime before copying the Termux package's real ELF executables.
            listOf("python", "python3", "python3.14", "pip", "pip3", "venv").forEach { legacy ->
                val oldCommand = File(prefix, "bin/$legacy")
                if (oldCommand.exists() || isSymlink(oldCommand)) oldCommand.delete()
            }
            copyAssetTree(context, BOOTSTRAP_ASSET, prefix)
            installPackageSymlinks(prefix)
            initializePackageManagerState(prefix)
            // The Python runtime ZIP is packaged next to the bootstrap directory,
            // not inside it, so copy it into PREFIX before extracting.
            val pythonRuntime = File(prefix, PYTHON_RUNTIME_ASSET)
            copyAssetFile(context, PYTHON_RUNTIME_ASSET, pythonRuntime)
            extractTermuxPythonRuntime(pythonRuntime, File(prefix, "lib"))
            installPythonHelpers(prefix)
            // APK assets do not retain Unix executable mode bits. Restore them
            // after extraction or the shell reports "Permission denied" for
            // python, pip, and every other packaged launcher.
            makeBinExecutables(File(prefix, "bin"))
            // Git invokes transport helpers from libexec/git-core directly.
            makeBinExecutables(File(prefix, "libexec"))
            val profile = File(prefix, "etc/profile")
            val bashrc = File(prefix, "etc/bash.bashrc")
            if (profile.isFile) copyFile(profile, File(home, ".profile"))
            if (bashrc.isFile) copyFile(bashrc, File(home, ".bashrc"))
            marker.writeText(BOOTSTRAP_VERSION)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install terminal bootstrap", e)
        }
    }

    /**
     * Extract the Python runtime from one APK asset ZIP. Keeping Python's
     * underscore-prefixed package directories inside a ZIP avoids asset
     * packaging filters that otherwise omit pip/_internal and stdlib subtrees.
     */
    private fun extractTermuxPythonRuntime(archive: File, destination: File) {
        if (!archive.isFile) {
            throw IllegalStateException("Missing Termux Python runtime: ${archive.absolutePath}")
        }
        val root = destination.canonicalFile
        // Do not mix files from the previous standalone CPython payload with
        // the Termux package's standard library after an in-place app update.
        val oldStdlib = File(root, "python3.14")
        if (oldStdlib.exists() && !oldStdlib.deleteRecursively()) {
            throw IllegalStateException("Cannot replace old Python standard library: ${oldStdlib.absolutePath}")
        }
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(root, entry.name).canonicalFile
                if (output.path != root.path && !output.path.startsWith(root.path + File.separator)) {
                    throw IllegalStateException("Unsafe Python runtime ZIP entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (!output.exists() && !output.mkdirs()) {
                        throw IllegalStateException("Cannot create ${output.absolutePath}")
                    }
                } else {
                    output.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IllegalStateException("Cannot create ${parent.absolutePath}")
                        }
                    }
                    output.outputStream().buffered().use { target -> zip.copyTo(target) }
                }
                zip.closeEntry()
            }
        }
        archive.delete()
    }

    private fun copyAssetFile(context: Context, assetPath: String, destination: File) {
        destination.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) {
                throw IllegalStateException("Cannot create ${it.absolutePath}")
            }
        }
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isNotEmpty()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw IllegalStateException("Cannot create ${destination.absolutePath}")
            }
            children.forEach { child ->
                copyAssetTree(context, "$assetPath/$child", File(destination, child))
            }
            return
        }

        destination.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) {
                throw IllegalStateException("Cannot create ${it.absolutePath}")
            }
        }
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Recreate package-manager symlinks that cannot be stored in APK assets.
     * The build manifest stores paths relative to PREFIX and never embeds
     * build-machine or Termux absolute paths.
     */
    private fun installPackageSymlinks(prefix: File) {
        val manifest = File(prefix, "package_symlinks.tsv")
        if (!manifest.isFile) {
            throw IllegalStateException("Missing package symlink manifest: ${manifest.absolutePath}")
        }
        val root = prefix.canonicalFile
        manifest.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val fields = line.split('\t')
            if (fields.size != 2) {
                throw IllegalStateException("Invalid package symlink manifest entry: $line")
            }
            val linkPath = fields[0]
            val targetPath = fields[1]
            fun isSafeRelative(path: String): Boolean =
                path.isNotBlank() && !path.startsWith("/") &&
                    path.split('/').none { it.isBlank() || it == "." || it == ".." }

            if (!isSafeRelative(linkPath) || !isSafeRelative(targetPath)) {
                throw IllegalStateException("Unsafe package symlink manifest entry: $line")
            }

            val link = File(root, linkPath)
            val parent = link.parentFile
                ?: throw IllegalStateException("Package symlink has no parent: $linkPath")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("Cannot create package symlink directory: ${parent.absolutePath}")
            }
            val safeParent = parent.canonicalFile
            if (safeParent.path != root.path && !safeParent.path.startsWith(root.path + File.separator)) {
                throw IllegalStateException("Package symlink parent escapes PREFIX: $linkPath")
            }

            val target = File(root, targetPath).canonicalFile
            if (!target.isFile && !target.isDirectory) {
                throw IllegalStateException("Package symlink target is missing: $targetPath")
            }
            if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
                throw IllegalStateException("Package symlink target escapes PREFIX: $targetPath")
            }
            if (!createLink(File(safeParent, link.name), target)) {
                throw IllegalStateException("Cannot create package symlink: $linkPath -> $targetPath")
            }
        }
        manifest.delete()
    }

    /** Initialize writable APT/dpkg state outside the read-only APK assets. */
    private fun initializePackageManagerState(prefix: File) {
        val directories = listOf(
            "var/lib/dpkg", "var/lib/dpkg/updates", "var/lib/dpkg/info",
            "var/lib/dpkg/parts", "var/lib/dpkg/triggers", "var/lib/dpkg/alternatives",
            "var/lib/apt/lists/partial", "var/cache/apt/archives/partial",
            "var/log/apt", "etc/apt/apt.conf.d", "etc/apt/sources.list.d",
            "etc/apt/trusted.gpg.d"
        )
        directories.forEach { relative ->
            val directory = File(prefix, relative)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Cannot create package-manager directory: ${directory.absolutePath}")
            }
        }
        val status = File(prefix, "var/lib/dpkg/status")
        if (!status.exists() && !status.createNewFile()) {
            throw IllegalStateException("Cannot initialize dpkg status database: ${status.absolutePath}")
        }
        val aptConf = File(prefix, "etc/apt/apt.conf")
        val root = prefix.absolutePath
        val config = """
            Dir::Root "$root";
            Dir::Etc "$root/etc/apt";
            Dir::Etc::sourcelist "sources.list";
            Dir::Etc::sourceparts "sources.list.d";
            Dir::Etc::main "apt.conf";
            Dir::Etc::parts "apt.conf.d";
            Dir::Etc::trusted "trusted.gpg";
            Dir::Etc::trustedparts "trusted.gpg.d";
            Dir::State "$root/var/lib/apt";
            Dir::State::status "$root/var/lib/dpkg/status";
            Dir::State::lists "lists";
            Dir::Cache "$root/var/cache/apt";
            Dir::Cache::archives "archives";
            Dir::Log "$root/var/log/apt";
            DPkg::Options { "--admindir=$root/var/lib/dpkg"; };
        """.trimIndent() + "\n"
        if (!aptConf.exists() || aptConf.readText() != config) aptConf.writeText(config)
    }
    /** Repair Python command entry points and expose the legacy Crypto namespace. */
    private fun installPythonHelpers(prefix: File) {
        val sitePackages = File(prefix, "lib/python3.14/site-packages")
        val cryptodome = File(sitePackages, "Cryptodome")
        val cryptoAlias = File(sitePackages, "Crypto")
        if (cryptodome.isDirectory && !cryptoAlias.exists() && !isSymlink(cryptoAlias)) {
            try {
                Os.symlink(cryptodome.absolutePath, cryptoAlias.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Could not create Crypto compatibility alias", e)
            }
        }
        listOf("pip", "pip3", "pip3.14").forEach { command ->
            val launcher = File(prefix, "bin/$command")
            val script = "#!/system/bin/sh\nexec \"${prefix.absolutePath}/bin/python\" -m pip \"\$@\"\n"
            launcher.writeText(script)
            Os.chmod(launcher.absolutePath, 0b111101101)
        }
    }
    private fun makeBinExecutables(bin: File) {
        if (!bin.isDirectory) return
        bin.walkTopDown()
            .filter { it.isFile && !isSymlink(it) }
            .forEach { executable ->
                try {
                    Os.chmod(executable.absolutePath, 0b111101101) // 0755
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot make command executable: ${executable.absolutePath}", e)
                    throw e
                }
            }
    }

    private fun copyFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        source.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun readLibraryMap(context: Context): Properties {
        val properties = Properties()
        try {
            context.assets.open(LINK_MAP_ASSET).use { properties.load(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse native library map", e)
        }
        return properties
    }

    private fun installCommandLinks(
        context: Context,
        nativeDir: File,
        prefix: File
    ): Set<String> {
        val properties = readLibraryMap(context)
        val installed = linkedSetOf<String>()
        val prefixPath = prefix.canonicalPath + File.separator

        properties.stringPropertyNames().forEach libraryLoop@ { libraryName ->
            val source = File(nativeDir, libraryName)
            if (!source.isFile) return@libraryLoop

            val aliases = properties.getProperty(libraryName)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            aliases.forEach aliasLoop@ { relativePath ->
                if (relativePath.startsWith("/") ||
                    relativePath.split('/').any { it == ".." }
                ) {
                    Log.w(TAG, "Ignoring unsafe native alias: $relativePath")
                    return@aliasLoop
                }

                try {
                    // Resolve the parent directory, but do not canonicalize the
                    // final path: an existing alias is a symlink to nativeLibraryDir,
                    // so canonicalFile would follow it outside PREFIX and reject it
                    // on every subsequent app launch.
                    val unresolved = File(prefix, relativePath).absoluteFile
                    val parent = unresolved.parentFile
                        ?: throw IllegalArgumentException("Alias has no parent: $relativePath")
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IllegalStateException("Cannot create alias directory: ${parent.absolutePath}")
                    }
                    val canonicalParent = parent.canonicalFile
                    val destination = File(canonicalParent, unresolved.name)
                    if (!destination.absolutePath.startsWith(prefixPath)) {
                        Log.w(TAG, "Ignoring alias outside PREFIX: $relativePath")
                        return@aliasLoop
                    }
                    if (createLink(destination, source)) {
                        installed.add(destination.name)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create native alias $relativePath", e)
                }
            }
        }
        return installed
    }

    private fun createLink(link: File, target: File): Boolean {
        if (link.absolutePath == target.absolutePath) return true
        try {
            if (link.exists() || isSymlink(link)) {
                if (!link.delete()) {
                    Log.w(TAG, "Failed to remove old symlink: ${link.absolutePath}")
                    return false
                }
            }
            Os.symlink(target.absolutePath, link.absolutePath)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create symlink: ${link.absolutePath}", e)
            return false
        }
    }

    private fun isSymlink(file: File): Boolean = try {
        Os.readlink(file.absolutePath)
        true
    } catch (_: Exception) {
        false
    }
}
