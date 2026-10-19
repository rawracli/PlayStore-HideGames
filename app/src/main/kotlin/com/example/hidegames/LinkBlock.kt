package com.example.hidegames

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * Memblokir pembukaan halaman detail game lewat link langsung:
 *   - market://details?id=<pkg>
 *   - https://play.google.com/store/apps/details?id=<pkg>
 *
 * Dua jalur:
 *  1. Game yang sudah dikenal (ada di [GameRegistry]): activity Play Store yang
 *     membawa intent detail langsung ditutup, halaman tidak pernah tampil.
 *  2. Game yang belum dikenal: halaman sempat dimuat, tetapi begitu dokumennya
 *     dikenali sebagai game, activity detailnya (dan activity teratas) ditutup.
 *
 * Semua activity di proses Play Store dipantau lewat hook android.app.Activity.
 */
object LinkBlock {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var logCount = 0

    // pkg -> activity yang sedang menampilkan halaman detail pkg tersebut.
    private val details: MutableList<Pair<String, WeakReference<Any>>> =
        Collections.synchronizedList(ArrayList())

    // Activity yang terakhir resume (biasanya activity teratas / translucent overlay).
    @Volatile private var topResumed: WeakReference<Any>? = null

    // Activity yang sudah kita mintai finish, agar tidak dobel.
    private val finishing: MutableSet<Int> =
        Collections.synchronizedSet(HashSet<Int>())

    private fun log(msg: String) {
        if (logCount < 250) {
            logCount++
            XposedBridge.log("HG-link $msg")
        }
    }

    /** True bila ada halaman detail untuk pkg ini yang sedang terbuka. */
    fun isWatched(pkg: String?): Boolean {
        if (pkg == null) return false
        return details.any { it.first == pkg }
    }

    /** Ambil package id dari data intent (query "id"), baik market:// maupun https. */
    fun packageOf(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val id = data.getQueryParameter("id")
        return if (id.isNullOrEmpty()) null else id
    }

    private fun intentOf(activity: Any): Intent? = try {
        XposedHelpers.callMethod(activity, "getIntent") as? Intent
    } catch (t: Throwable) {
        null
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        GameRegistry.init(cl)

        // Entry point baru (termasuk trampoline deep link): cek intent, blokir bila game.
        try {
            XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject ?: return
                    register(a, intentOf(a), "onCreate")
                }
            })

            // Link masuk saat Play Store sudah berjalan.
            XposedBridge.hookAllMethods(Activity::class.java, "onNewIntent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject ?: return
                    register(a, param.args.getOrNull(0) as? Intent, "onNewIntent")
                }
            })

            // Simpan activity detail, untuk menutup game yang baru dikenali (jalur 2).
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject ?: return
                    topResumed = WeakReference(a)
                    register(a, intentOf(a), "onResume")
                }
            })

            XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject ?: return
                    details.removeAll { it.second.get() === a || it.second.get() == null }
                    if (topResumed?.get() === a) topResumed = null
                    finishing.remove(System.identityHashCode(a))
                }
            })
        } catch (t: Throwable) {
            log("hook activity gagal: $t")
        }

        log("terpasang pada Activity onCreate/onNewIntent/onResume/onDestroy")
    }

    private fun register(activity: Any, intent: Intent?, from: String) {
        val pkg = packageOf(intent) ?: return
        if (details.none { it.second.get() === activity }) {
            details.add(pkg to WeakReference(activity))
        }
        if (from == "onCreate") log("$from pkg=$pkg act=" + activity.javaClass.simpleName)
        blockIfGame(pkg, activity)
    }

    private fun blockIfGame(pkg: String?, activity: Any?) {
        if (activity == null || pkg == null) return
        if (!GameRegistry.isGame(pkg)) return
        log("blokir link game: $pkg act=" + activity.javaClass.simpleName)
        finish(activity)
        // Tutup juga overlay translucent teratas (halaman detail bisa hidup di situ).
        topResumed?.get()?.let { finish(it) }
    }

    /** Dipanggil [GameRegistry] saat sebuah game baru dikenali (jalur 2). */
    fun onGameLearned(pkg: String) {
        // Hanya bertindak bila memang ada halaman detail untuk pkg ini yang terbuka.
        // Saat browsing biasa, daftar ini kosong sehingga halaman tidak ikut tertutup.
        val matched = details.filter { it.first == pkg }.mapNotNull { it.second.get() }
        if (matched.isEmpty()) return
        val targets = matched.toMutableList()
        topResumed?.get()?.let { if (targets.none { t -> t === it }) targets.add(it) }
        var closed = 0
        for (a in targets) {
            if (finish(a)) closed++
        }
        if (closed > 0) log("halaman game telat dikenali, tutup $closed activity: $pkg")
    }

    private fun close(activity: Any?) {
        if (activity != null) finish(activity)
    }

    private fun finish(activity: Any?): Boolean {
        if (activity == null) return false
        if (!finishing.add(System.identityHashCode(activity))) return false
        handler.post {
            try {
                XposedHelpers.callMethod(activity, "finish")
            } catch (t: Throwable) {
                // abaikan
            }
        }
        return true
    }
}
