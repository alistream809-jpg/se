package app.rockstarport

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.zip.CRC32

object L {
    @Volatile var tr = true
    fun t(en: String, trs: String) = if (tr) trs else en
}

fun fmt(b: Long): String {
    if (b <= 0) return "0 B"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.2f %s", v, u[i])
}

object Dirs {
    /** Same folder name the engine's own launcher suggests ("GrandV-assets"). */
    val dest: File get() = File(Environment.getExternalStorageDirectory(), "GrandV-assets")
    val marker: File get() = File(dest, ".rp_installed")
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("rp", Context.MODE_PRIVATE)
    var zipUri: String? get() = p.getString("zip", null); set(v) { p.edit().putString("zip", v).apply() }
    var zipName: String? get() = p.getString("zipName", null); set(v) { p.edit().putString("zipName", v).apply() }
    var enginePkg: String get() = p.getString("engine", "dev.muguet") ?: "dev.muguet"; set(v) { p.edit().putString("engine", v).apply() }
    var tr: Boolean get() = p.getBoolean("tr", Locale.getDefault().language == "tr"); set(v) { p.edit().putBoolean("tr", v).apply() }
    var hintShown: Boolean get() = p.getBoolean("hint", false); set(v) { p.edit().putBoolean("hint", v).apply() }
}

/** Shared install state, observed by the UI and written by InstallService. */
object Inst {
    @Volatile var running = false
    @Volatile var cancel = false
    @Volatile var progress: Progress? = null
    @Volatile var message: String = ""
    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun notifyUi() { val l = listener ?: return; main.post { l() } }
}

data class Progress(val done: Long, val total: Long, val file: Int, val files: Int, val current: String, val phase: String)

data class Scan(
    val prefix: String, val build: String?, val manifest: Boolean, val wasm: Boolean, val shaders: Boolean,
    val fileCount: Int, val totalBytes: Long, val tops: List<String>
) {
    val ok get() = build != null && manifest && wasm && shaders
    fun missing(): List<String> {
        val m = ArrayList<String>()
        if (!manifest) m.add("data/manifest.json")
        if (!wasm) m.add("b/<build>/game.wasm")
        if (!shaders) m.add("b/<build>/shaders/index.json")
        return m
    }
}

private class Todo(val e: ZipArchiveEntry, val out: File, val size: Long)

object Zips {
    fun norm(n: String) = n.replace('\\', '/').trimStart('/')

    /** Opens the ZIP through a seekable channel (no copy of the 19 GB file is made). */
    fun <T> withZip(ctx: Context, uri: Uri, block: (ZipFile) -> T): T {
        val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open ZIP")
        return pfd.use { p -> FileInputStream(p.fileDescriptor).use { fis -> ZipFile(fis.channel).use { zf -> block(zf) } } }
    }

    private val wasmRe = Regex("^(.*?)b/([^/]+)/game\\.wasm$")

    fun scan(zf: ZipFile): Scan {
        val entries = Collections.list(zf.entries).filter { !it.isDirectory }
        val names = entries.map { norm(it.name) }
        val man = names.filter { it == "data/manifest.json" || it.endsWith("/data/manifest.json") }.minByOrNull { it.length }
        val prefix: String = man?.removeSuffix("data/manifest.json")
            ?: (names.mapNotNull { wasmRe.find(it)?.groupValues?.get(1) }.minByOrNull { it.length } ?: "")
        val under = entries.filter { norm(it.name).startsWith(prefix) }
        val rel = HashMap<String, Long>()
        for (e in under) rel[norm(e.name).substring(prefix.length)] = e.size
        var build: String? = null; var wasm = false; var shaders = false
        for (k in rel.keys.sorted()) {
            val m = Regex("^b/([^/]+)/game\\.wasm$").find(k) ?: continue
            val id = m.groupValues[1]
            val w = (rel[k] ?: 0L) > 0
            val s = rel.containsKey("b/$id/shaders/index.json")
            if (w && (build == null || (s && !shaders))) { build = id; wasm = true; shaders = s }
        }
        val tops = names.map { it.substringBefore('/') }.distinct().take(8)
        return Scan(prefix, build, (rel["data/manifest.json"] ?: 0L) > 0, wasm, shaders, under.size, under.sumOf { maxOf(it.size, 0L) }, tops)
    }
}

object Installer {
    /** Real extraction: streaming, CRC32-verified, resumable (complete files are skipped). Returns a result message. */
    fun run(ctx: Context, uri: Uri, dest: File, cb: (Progress) -> Unit): String {
        return Zips.withZip(ctx, uri) { zf ->
            cb(Progress(0, 0, 0, 0, "", L.t("Reading ZIP index…", "ZIP dizini okunuyor…")))
            val scan = Zips.scan(zf)
            if (!scan.ok) {
                return@withZip L.t("This ZIP is not a compatible web-port package. Missing: ", "Bu ZIP uyumlu bir web-port paketi değil. Eksik: ") +
                    scan.missing().joinToString() + L.t(". ZIP root: ", ". ZIP kökü: ") + scan.tops.joinToString()
            }
            dest.mkdirs()
            if (!dest.isDirectory || !dest.canWrite())
                return@withZip L.t("Cannot write to ", "Yazılamıyor: ") + dest.path + L.t(". Grant 'All files access'.", ". 'Tüm dosyalara erişim' izni ver.")
            Dirs.marker.delete()
            val root = dest.canonicalPath + File.separator
            val todo = ArrayList<Todo>()
            var skipped = 0; var skippedBytes = 0L; var remaining = 0L
            for (e in Collections.list(zf.entries)) {
                if (e.isDirectory) continue
                val n = Zips.norm(e.name)
                if (!n.startsWith(scan.prefix)) continue
                val rel = n.substring(scan.prefix.length)
                if (rel.isEmpty()) continue
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(root)) continue // zip-slip guard
                val size = maxOf(e.size, 0L)
                if (out.isFile && out.length() == size) { skipped++; skippedBytes += size } else { todo.add(Todo(e, out, size)); remaining += size }
            }
            val avail = StatFs(dest.path).availableBytes
            if (remaining > avail)
                return@withZip L.t("Not enough space. Need ", "Yetersiz alan. Gereken ") + fmt(remaining) + L.t(", free ", ", boş ") + fmt(avail)
            val total = skippedBytes + remaining
            var done = skippedBytes
            val errors = ArrayList<String>()
            var idx = skipped; var extracted = 0; var last = 0L
            val buf = ByteArray(256 * 1024)
            for (t in todo) {
                if (Inst.cancel || errors.size > 25) break
                idx++
                val name = t.out.name
                if (!zf.canReadEntryData(t.e)) { errors.add("unsupported: $name"); done += t.size; continue }
                t.out.parentFile?.mkdirs()
                val part = File(t.out.path + ".part")
                val crc = CRC32()
                try {
                    zf.getInputStream(t.e).use { ins ->
                        part.outputStream().use { os ->
                            while (!Inst.cancel) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n); crc.update(buf, 0, n); done += n
                                val now = SystemClock.elapsedRealtime()
                                if (now - last > 200) { last = now; cb(Progress(done, total, idx, skipped + todo.size, name, L.t("Extracting", "Çıkarılıyor"))) }
                            }
                        }
                    }
                    if (Inst.cancel) { part.delete(); break }
                    if (t.e.crc != -1L && crc.value != t.e.crc) { part.delete(); errors.add("CRC: ${t.e.name}"); continue }
                    if (part.length() != t.size) { part.delete(); errors.add("size: ${t.e.name}"); continue }
                    t.out.delete()
                    if (!part.renameTo(t.out)) { errors.add("rename: ${t.e.name}"); continue }
                    extracted++
                } catch (ex: IOException) {
                    part.delete(); errors.add("${t.e.name}: ${ex.message}")
                }
            }
            cb(Progress(done, total, idx, skipped + todo.size, "", L.t("Verifying", "Doğrulanıyor")))
            val v = Validator.check(dest)
            when {
                Inst.cancel -> L.t("Cancelled. Press Install again to resume; finished files are kept.", "İptal edildi. Devam etmek için tekrar Kur'a bas; biten dosyalar korunur.")
                errors.isNotEmpty() -> L.t("Finished with ${errors.size} problem(s): ", "${errors.size} sorunla bitti: ") + errors.take(3).joinToString("; ")
                !v.ok -> L.t("Extracted, but validation failed: ", "Çıkarıldı ama doğrulama başarısız: ") + v.problems().joinToString()
                else -> {
                    Dirs.marker.writeText("build=${v.build}\nfiles=${extracted + skipped}\ntime=${System.currentTimeMillis()}\n")
                    L.t("Installed and verified. Build ${v.build}; $extracted files extracted, $skipped already present.", "Kuruldu ve doğrulandı. Sürüm ${v.build}; $extracted dosya çıkarıldı, $skipped zaten vardı.")
                }
            }
        }
    }
}

data class Validation(val manifest: Boolean, val build: String?, val wasm: Boolean, val shaders: Boolean, val partials: Int, val sizeBytes: Long, val verified: Boolean) {
    val ok get() = build != null && manifest && wasm && shaders && partials == 0
    fun problems(): List<String> {
        val m = ArrayList<String>()
        if (!manifest) m.add("data/manifest.json")
        if (!wasm) m.add("game.wasm")
        if (!shaders) m.add("shaders/index.json")
        if (partials > 0) m.add("$partials ${L.t("incomplete file(s)", "yarım dosya")}")
        return m
    }
}

object Validator {
    fun check(dest: File): Validation {
        val man = File(dest, "data/manifest.json").let { it.isFile && it.length() > 0 }
        var build: String? = null; var wasm = false; var shaders = false
        File(dest, "b").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val w = File(d, "game.wasm").let { it.isFile && it.length() > 0 }
            val s = File(d, "shaders/index.json").isFile
            if (w && (build == null || (s && !shaders))) { build = d.name; wasm = true; shaders = s }
        }
        var partials = 0; var size = 0L
        if (dest.isDirectory) dest.walkTopDown().forEach { f -> if (f.isFile) { size += f.length(); if (f.name.endsWith(".part")) partials++ } }
        return Validation(man, build, wasm, shaders, partials, size, Dirs.marker.isFile)
    }
}

data class Check(val name: String, val value: String, val level: Int) // 0 ok, 1 warning, 2 blocking

object Compat {
    fun hasStorage(a: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else a.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun run(a: Activity, s: Store, v: Validation?): List<Check> {
        val o = ArrayList<Check>()
        o.add(Check("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", if (Build.VERSION.SDK_INT >= 26) 0 else 2))
        val abi = Build.SUPPORTED_ABIS.toList()
        o.add(Check(L.t("CPU architecture", "CPU mimarisi"), abi.joinToString(), if ("arm64-v8a" in abi) 0 else 2))
        var soc = "${Build.MANUFACTURER} ${Build.MODEL}"
        if (Build.VERSION.SDK_INT >= 31) soc += " · ${Build.SOC_MODEL}"
        o.add(Check(L.t("Device", "Cihaz"), "$soc · ${Runtime.getRuntime().availableProcessors()} cores", 0))
        val pm = a.packageManager
        val vk11 = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)
        val vk10 = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x400003)
        o.add(Check("Vulkan", if (vk11) "1.1+" else if (vk10) "1.0" else L.t("not reported", "bildirilmiyor"), if (vk11) 0 else if (vk10) 1 else 2))
        val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        o.add(Check("OpenGL ES", am.deviceConfigurationInfo.glEsVersion, 0))
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        o.add(Check("RAM", String.format(Locale.US, "%.1f GB", gb) + L.t(" (engine recommends 12 GB)", " (motor 12 GB öneriyor)"), if (gb >= 11.0) 0 else 1))
        @Suppress("DEPRECATION") val hz = a.windowManager.defaultDisplay.refreshRate
        o.add(Check(L.t("Display", "Ekran"), String.format(Locale.US, "%.0f Hz", hz), 0))
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (e: Exception) { 0L }
        o.add(Check(L.t("Free storage", "Boş alan"), fmt(free), if (free > 2L * 1024 * 1024 * 1024) 0 else 1))
        o.add(Check(L.t("All files access", "Tüm dosyalara erişim"), if (hasStorage(a)) L.t("granted", "verildi") else L.t("not granted", "verilmedi"), if (hasStorage(a)) 0 else 1))
        val info = try { pm.getPackageInfo(s.enginePkg, 0) } catch (e: Exception) { null }
        o.add(Check(L.t("Game runtime", "Oyun motoru"), if (info != null) "${s.enginePkg} ${info.versionName ?: ""}" else L.t("NOT INSTALLED (${s.enginePkg}). A compatible runtime is required.", "KURULU DEĞİL (${s.enginePkg}). Uyumlu bir motor gerekli."), if (info != null) 0 else 2))
        if (v == null) o.add(Check(L.t("Game data", "Oyun verisi"), L.t("not checked", "kontrol edilmedi"), 2))
        else {
            o.add(Check("data/manifest.json", if (v.manifest) "OK" else L.t("missing", "yok"), if (v.manifest) 0 else 2))
            o.add(Check("game.wasm", if (v.wasm) "OK · build ${v.build}" else L.t("missing", "yok"), if (v.wasm) 0 else 2))
            o.add(Check("shaders/index.json", if (v.shaders) "OK" else L.t("missing", "yok"), if (v.shaders) 0 else 2))
            if (v.partials > 0) o.add(Check(L.t("Incomplete files", "Yarım dosyalar"), "${v.partials}", 2))
        }
        return o
    }
}
