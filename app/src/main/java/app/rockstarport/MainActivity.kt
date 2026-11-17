package app.rockstarport

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ORANGE = 0xFFFF6A00.toInt()
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private lateinit var store: Store
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var validation: Validation? = null

    private lateinit var vInstall: TextView
    private lateinit var vBuild: TextView
    private lateinit var vEngine: TextView
    private lateinit var vStorage: TextView
    private lateinit var vZip: TextView
    private lateinit var tvPct: TextView
    private lateinit var tvMsg: TextView
    private lateinit var bar: ProgressBar
    private lateinit var bImport: Button
    private lateinit var bInstall: Button
    private lateinit var bPlay: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun c(v: Long) = v.toInt()

    private fun shape(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun btn(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = true; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = shape(if (primary) ORANGE else c(0x66202020), 12, 1, if (primary) ORANGE else c(0x88FF6A00))
        stateListAnimator = null
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { onClick() }
    }

    private fun enable(b: Button, on: Boolean) { b.isEnabled = on; b.alpha = if (on) 1f else 0.4f }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        background = shape(c(0x99141414), 16, 1, c(0x55FF6A00)); elevation = dp(6).toFloat()
    }

    private fun row(parent: LinearLayout, label: String): TextView {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        r.addView(TextView(this).apply { text = label.uppercase(); setTextColor(c(0xFF9A9A9A)); textSize = 11f; letterSpacing = 0.1f }, LinearLayout.LayoutParams(dp(110), WRAP))
        val v = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        r.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
        parent.addView(r)
        return v
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(h)) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this); L.tr = store.tr
        setContentView(buildUi())
        hideBars()
    }

    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideBars() }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onResume() {
        super.onResume()
        Inst.listener = { updateProgress() }
        updateProgress(); refresh()
    }

    override fun onPause() { super.onPause(); Inst.listener = null }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.bg_main); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f }, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c(0xF2050505), c(0xB3050505), c(0xE6050505)))
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(28), dp(18), dp(28), dp(18)); alpha = 0f; translationY = dp(16).toFloat() }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // Left: brand + cover
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply { text = "ROCKSTAR PORT"; setTextColor(ORANGE); textSize = 24f; typeface = Typeface.create("sans-serif-black", Typeface.BOLD); letterSpacing = 0.18f; setShadowLayer(14f, 0f, 3f, c(0xAAFF6A00)) })
        left.addView(TextView(this).apply { text = "GTA V ANDROID PORT LAUNCHER"; setTextColor(c(0xFF9A9A9A)); textSize = 10f; letterSpacing = 0.25f; setPadding(0, 0, 0, dp(10)) })
        left.addView(ImageView(this).apply {
            setImageResource(R.drawable.cover_main); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat()) } }
            clipToOutline = true; elevation = dp(10).toFloat()
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(left, LinearLayout.LayoutParams(0, MATCH, 0.9f).apply { rightMargin = dp(22) })

        // Right: status, progress, buttons
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        content.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1.35f))

        val st = card()
        vInstall = row(st, L.t("Installation", "Kurulum"))
        vBuild = row(st, L.t("Game build", "Oyun sürümü"))
        vEngine = row(st, L.t("Runtime", "Motor"))
        vStorage = row(st, L.t("Storage", "Depolama"))
        vZip = row(st, "ZIP")
        col.addView(st)
        col.addView(gap(10))

        val pg = card()
        tvPct = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(ORANGE); progressBackgroundTintList = ColorStateList.valueOf(c(0xFF333333))
        }
        tvMsg = TextView(this).apply { setTextColor(c(0xFFCCCCCC)); textSize = 12f; setPadding(0, dp(4), 0, 0) }
        pg.addView(tvPct); pg.addView(bar, LinearLayout.LayoutParams(MATCH, dp(10))); pg.addView(tvMsg)
        col.addView(pg)
        col.addView(gap(10))

        bImport = btn(L.t("Import ZIP", "ZIP İçe Aktar"), false) { pickZip() }
        bInstall = btn(L.t("Install game", "Oyunu Kur"), false) { installClick() }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(bImport, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        r1.addView(bInstall, LinearLayout.LayoutParams(0, dp(46), 1f))
        col.addView(r1); col.addView(gap(8))

        bPlay = btn(L.t("PLAY GAME", "OYNA"), true) { play() }.apply { textSize = 16f }
        col.addView(bPlay, LinearLayout.LayoutParams(MATCH, dp(54))); col.addView(gap(8))

        val r3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r3.addView(btn(L.t("Files", "Dosyalar"), false) { showFiles() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        r3.addView(btn(L.t("Compatibility", "Uyumluluk"), false) { showChecks(Compat.run(this, store, validation), L.t("Compatibility check", "Uyumluluk kontrolü")) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        r3.addView(btn(L.t("Settings", "Ayarlar"), false) { showSettings() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        col.addView(r3)

        content.animate().alpha(1f).translationY(0f).setDuration(650).start()
        return root
    }

    // ---------- state ----------
    private fun refresh() {
        io.execute {
            val v = Validator.check(Dirs.dest)
            val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (e: Exception) { 0L }
            ui.post { validation = v; render(v, free) }
        }
    }

    private fun render(v: Validation, free: Long) {
        val green = c(0xFF4CD964); val red = c(0xFFFF5A4D); val amber = c(0xFFFFB020)
        when {
            v.ok -> { vInstall.text = if (v.verified) L.t("INSTALLED · verified", "KURULU · doğrulandı") else L.t("DETECTED · not verified by launcher", "ALGILANDI · launcher doğrulamadı"); vInstall.setTextColor(if (v.verified) green else amber) }
            v.manifest || v.wasm || v.partials > 0 -> { vInstall.text = L.t("INCOMPLETE: ", "EKSİK: ") + v.problems().joinToString(); vInstall.setTextColor(red) }
            else -> { vInstall.text = L.t("NOT INSTALLED", "KURULU DEĞİL"); vInstall.setTextColor(Color.WHITE) }
        }
        vBuild.text = v.build ?: "—"
        val info = try { packageManager.getPackageInfo(store.enginePkg, 0) } catch (e: Exception) { null }
        vEngine.text = if (info != null) "${store.enginePkg} ${info.versionName ?: ""}" else L.t("not installed (${store.enginePkg})", "kurulu değil (${store.enginePkg})")
        vEngine.setTextColor(if (info != null) green else red)
        vStorage.text = L.t("game ", "oyun ") + fmt(v.sizeBytes) + L.t(" · free ", " · boş ") + fmt(free)
        vZip.text = store.zipName ?: L.t("none selected", "seçilmedi")
        val busy = Inst.running
        enable(bImport, !busy); enable(bInstall, busy || store.zipUri != null)
        bInstall.text = if (busy) L.t("Cancel", "İptal") else L.t("Install game", "Oyunu Kur")
        enable(bPlay, v.ok && !busy)
    }

    private fun updateProgress() {
        val p = Inst.progress
        if (Inst.running) {
            if (p != null && p.total > 0) {
                bar.progress = (p.done * 1000 / p.total).toInt()
                tvPct.text = String.format(java.util.Locale.US, "%.1f%%  ·  %s / %s  ·  %d/%d", p.done * 100.0 / p.total, fmt(p.done), fmt(p.total), p.file, p.files)
                tvMsg.text = "${p.phase}: ${p.current}"
            } else { bar.progress = 0; tvPct.text = "…"; tvMsg.text = p?.phase ?: L.t("Starting…", "Başlıyor…") }
        } else {
            bar.progress = 0; tvPct.text = ""
            tvMsg.text = Inst.message
        }
        if (!Inst.running) refresh() else { enable(bImport, false); bInstall.text = L.t("Cancel", "İptal"); enable(bInstall, true); enable(bPlay, false) }
    }

    // ---------- actions ----------
    private fun pickZip() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 11)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != 11 || res != RESULT_OK || uri == null) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        var name = "archive.zip"
        contentResolver.query(uri, null, null, null, null)?.use { q -> if (q.moveToFirst()) { val i = q.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = q.getString(i) } }
        store.zipUri = uri.toString(); store.zipName = name
        tvMsg.text = L.t("Analyzing ZIP index…", "ZIP dizini inceleniyor…")
        render(validation ?: Validation(false, null, false, false, 0, 0, false), 0)
        io.execute {
            val msg = try {
                Zips.withZip(this, uri) { zf ->
                    val s = Zips.scan(zf)
                    if (s.ok) L.t("Compatible web-port package.\nBuild: ${s.build}\nFiles: ${s.fileCount}\nSize: ${fmt(s.totalBytes)}\nRoot in ZIP: '${s.prefix}'", "Uyumlu web-port paketi.\nSürüm: ${s.build}\nDosya: ${s.fileCount}\nBoyut: ${fmt(s.totalBytes)}\nZIP içindeki kök: '${s.prefix}'")
                    else L.t("NOT a compatible package. Missing: ${s.missing().joinToString()}\nZIP root contains: ${s.tops.joinToString()}\n\nA Windows/console GTA V install or a PC web-mirror without data/ and b/<build>/ cannot run on the Android runtime.", "Uyumlu paket DEĞİL. Eksik: ${s.missing().joinToString()}\nZIP kökü: ${s.tops.joinToString()}\n\nWindows/konsol GTA V kurulumu ya da data/ ve b/<build>/ içermeyen PC web-mirror'ı Android motorunda çalışmaz.")
                }
            } catch (t: Throwable) { L.t("Cannot read this ZIP: ", "ZIP okunamadı: ") + (t.message ?: t.javaClass.simpleName) }
            ui.post { tvMsg.text = msg; AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(name).setMessage(msg).setPositiveButton("OK", null).show(); refresh() }
        }
    }

    private fun installClick() {
        if (Inst.running) { Inst.cancel = true; tvMsg.text = L.t("Cancelling…", "İptal ediliyor…"); return }
        val uri = store.zipUri ?: return
        if (!Compat.hasStorage(this)) { requestStorage(); return }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        startForegroundService(Intent(this, InstallService::class.java).putExtra("uri", uri))
        Inst.running = true; Inst.message = ""; updateProgress()
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 3)
        tvMsg.text = L.t("Grant 'All files access', come back and press Install again.", "'Tüm dosyalara erişim' izni ver, geri dönüp tekrar Kur'a bas.")
    }

    private fun play() {
        val checks = Compat.run(this, store, validation)
        if (checks.any { it.level == 2 }) { showChecks(checks, L.t("Cannot start the game", "Oyun başlatılamıyor")); return }
        val intent = packageManager.getLaunchIntentForPackage(store.enginePkg)
        if (intent == null) { showChecks(checks, L.t("Runtime missing", "Motor eksik")); return }
        val go = { startActivity(intent) }
        if (!store.hintShown) {
            store.hintShown = true
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L.t("First launch", "İlk açılış"))
                .setMessage(L.t("If the engine asks for game data, choose 'Use a game files folder' and select:\n\n${Dirs.dest.path}\n\nThe first launch compiles the game and can take several minutes.", "Motor oyun verisi isterse 'Use a game files folder' seçip şu klasörü göster:\n\n${Dirs.dest.path}\n\nİlk açılışta oyun derlenir, birkaç dakika sürebilir."))
                .setPositiveButton("OK") { _, _ -> go() }.show()
        } else go()
    }

    // ---------- dialogs ----------
    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showChecks(list: List<Check>, title: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        for (k in list) {
            val col = when (k.level) { 0 -> c(0xFF4CD964); 1 -> c(0xFFFFB020); else -> c(0xFFFF5A4D) }
            val sb = SpannableStringBuilder("● ").apply { setSpan(ForegroundColorSpan(col), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            sb.append("${k.name}: ${k.value}")
            box.addView(TextView(this).apply { text = sb; setTextColor(Color.WHITE); textSize = 13f; setPadding(0, dp(3), 0, dp(3)) })
        }
        box.addView(TextView(this).apply {
            text = L.t("No FPS or graphics quality is promised. Actual performance depends on the engine and the device.", "FPS veya grafik kalitesi vaat edilmez. Gerçek performans motora ve cihaza bağlıdır.")
            setTextColor(c(0xFF9A9A9A)); textSize = 11f; setPadding(0, dp(10), 0, 0)
        })
        dlg().setTitle(title).setView(ScrollView(this).apply { addView(box) }).setPositiveButton("OK", null).show()
    }

    private fun showFiles() {
        val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(20), dp(10), dp(20), dp(10)); text = "…" }
        io.execute {
            val items = Dirs.dest.listFiles()?.sortedBy { it.name } ?: emptyList()
            val sb = StringBuilder(Dirs.dest.path + "\n\n")
            if (items.isEmpty()) sb.append(L.t("No game files.", "Oyun dosyası yok."))
            for (f in items) {
                val size = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                sb.append(if (f.isDirectory) "📁 " else "📄 ").append(f.name).append("  —  ").append(fmt(size)).append('\n')
            }
            ui.post { tv.text = sb.toString() }
        }
        dlg().setTitle(L.t("Game files", "Oyun dosyaları")).setView(ScrollView(this).apply { addView(tv) })
            .setNegativeButton(L.t("Delete all", "Hepsini sil")) { _, _ -> confirmDelete() }
            .setPositiveButton("OK", null).show()
    }

    private fun confirmDelete() {
        dlg().setTitle(L.t("Delete game files?", "Oyun dosyaları silinsin mi?"))
            .setMessage(Dirs.dest.path + L.t("\nThis cannot be undone.", "\nBu geri alınamaz."))
            .setPositiveButton(L.t("Delete", "Sil")) { _, _ ->
                tvMsg.text = L.t("Deleting…", "Siliniyor…")
                io.execute { val ok = Dirs.dest.deleteRecursively(); ui.post { tvMsg.text = if (ok) L.t("Deleted.", "Silindi.") else L.t("Some files could not be deleted.", "Bazı dosyalar silinemedi."); refresh() } }
            }.setNegativeButton(L.t("Cancel", "Vazgeç"), null).show()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun label(t: String) = TextView(this).apply { text = t.uppercase(); setTextColor(ORANGE); textSize = 11f; letterSpacing = 0.12f; setPadding(0, dp(12), 0, dp(4)) }
        fun note(t: String) = TextView(this).apply { text = t; setTextColor(c(0xFFBBBBBB)); textSize = 12f }

        box.addView(label(L.t("Language", "Dil")))
        val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lr.addView(btn("Türkçe", L.tr) { store.tr = true; recreate() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        lr.addView(btn("English", !L.tr) { store.tr = false; recreate() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        box.addView(lr)

        box.addView(label(L.t("Sound (media volume)", "Ses (medya ses seviyesi)")))
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        box.addView(SeekBar(this).apply {
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC); progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            progressTintList = ColorStateList.valueOf(ORANGE); thumbTintList = ColorStateList.valueOf(ORANGE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })

        box.addView(label(L.t("Graphics / resolution / FPS", "Grafik / çözünürlük / FPS")))
        box.addView(note(L.t("These options are applied by the game runtime itself and cannot be set from outside. Open the runtime's own launcher to change them.", "Bu seçenekleri oyun motoru kendi uygular, dışarıdan ayarlanamaz. Değiştirmek için motorun kendi launcher'ını aç.")))
        box.addView(btn(L.t("Open runtime", "Motoru aç"), false) {
            val i = packageManager.getLaunchIntentForPackage(store.enginePkg)
            if (i != null) startActivity(i) else tvMsg.text = L.t("Runtime not installed.", "Motor kurulu değil.")
        }, LinearLayout.LayoutParams(MATCH, dp(40)).apply { topMargin = dp(6) })

        box.addView(label(L.t("Runtime package", "Motor paketi")))
        val et = EditText(this).apply { setText(store.enginePkg); setTextColor(Color.WHITE); textSize = 13f; setSingleLine() }
        box.addView(et)
        box.addView(btn(L.t("Save", "Kaydet"), false) { store.enginePkg = et.text.toString().trim().ifEmpty { "dev.muguet" }; refresh() }, LinearLayout.LayoutParams(MATCH, dp(40)))

        box.addView(label(L.t("Storage", "Depolama")))
        box.addView(note(L.t("Theme: always dark.", "Tema: her zaman koyu.")))
        box.addView(btn(L.t("Clear temporary files (.part)", "Geçici dosyaları temizle (.part)"), false) {
            io.execute {
                var n = 0; var bytes = 0L
                if (!Inst.running) Dirs.dest.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList().forEach { bytes += it.length(); if (it.delete()) n++ }
                ui.post { tvMsg.text = if (Inst.running) L.t("Wait for the install to finish.", "Kurulumun bitmesini bekle.") else L.t("Removed $n temp file(s), ${fmt(bytes)}.", "$n geçici dosya silindi, ${fmt(bytes)}."); refresh() }
            }
        }, LinearLayout.LayoutParams(MATCH, dp(40)).apply { topMargin = dp(6) })
        box.addView(btn(L.t("Delete all game files", "Tüm oyun dosyalarını sil"), false) { confirmDelete() }, LinearLayout.LayoutParams(MATCH, dp(40)).apply { topMargin = dp(6) })
        box.addView(btn(L.t("All files access", "Tüm dosyalara erişim"), false) { requestStorage() }, LinearLayout.LayoutParams(MATCH, dp(40)).apply { topMargin = dp(6) })

        box.addView(label(L.t("About", "Hakkında")))
        box.addView(note("ROCKSTAR PORT 1.0.0\n" + L.t("Launcher only: imports and verifies your own game ZIP, then starts the installed runtime. It contains no game files and no engine. Not affiliated with Rockstar Games.", "Sadece launcher: kendi oyun ZIP'ini içe aktarır, doğrular ve kurulu motoru başlatır. Oyun dosyası veya motor içermez. Rockstar Games ile bağlantılı değildir.")))

        dlg().setTitle(L.t("Settings", "Ayarlar")).setView(ScrollView(this).apply { addView(box) }).setPositiveButton("OK", null).show()
    }
}
