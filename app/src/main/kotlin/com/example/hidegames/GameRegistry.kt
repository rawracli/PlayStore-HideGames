package com.example.hidegames

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XposedHelpers
import java.util.Collections

/**
 * Kumpulan package game yang pernah dikenali dari dokumen Play Store.
 *
 * Dipakai untuk memblokir pembukaan halaman detail game lewat link langsung
 * (market://details?id=...) secara instan, tanpa harus memuat halamannya lebih dulu.
 * Data disimpan di SharedPreferences agar tetap ada setelah Play Store ditutup.
 */
object GameRegistry {
    private const val PREFS = "hidegames_prefs"
    private const val KEY = "game_pkgs"

    private val pkgs: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    @Volatile private var loaded = false
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var persistQueued = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun init(cl: ClassLoader) {
        classLoader = cl
    }

    private fun appContext(): Context? = try {
        val at = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
        XposedHelpers.callStaticMethod(at, "currentApplication") as? Application
    } catch (t: Throwable) {
        null
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            appContext()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.getStringSet(KEY, null)?.let { pkgs.addAll(it) }
        } catch (t: Throwable) {
            // abaikan; mulai dengan himpunan kosong
        }
    }

    fun isGame(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        load()
        return pkgs.contains(pkg)
    }

    fun remember(pkg: String?) {
        if (pkg.isNullOrEmpty()) return
        load()
        if (pkgs.add(pkg)) {
            schedulePersist()
            LinkBlock.onGameLearned(pkg)
        }
    }

    private fun schedulePersist() {
        if (persistQueued) return
        persistQueued = true
        handler.postDelayed({
            persistQueued = false
            try {
                appContext()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    ?.edit()?.putStringSet(KEY, HashSet(pkgs))?.apply()
            } catch (t: Throwable) {
                // abaikan
            }
        }, 1000L)
    }
}
