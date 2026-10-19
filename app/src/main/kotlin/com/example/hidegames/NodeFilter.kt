package com.example.hidegames

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Menyaring anak (kartu) pada stream node Play Store yang dokumennya game.
 *
 * Titik hook: bckc.z(Throwable) -> bgcd(parent, ccnx, List<ccnw> anak, hasMore, error)
 * Daftar anak (bgcd.b) adalah yang dipakai UI untuk merender kartu.
 * Dokumen tiap anak ada di field bckc.w (flow berisi ahyg).
 *
 * Dua masalah yang ditangani:
 *  1. Indikator "memuat" tersisa pada section yang itemnya kita buang -> kita matikan
 *     flag hasMore (bgcd.a) setiap kali ada item dibuang.
 *  2. Kartu game yang dokumennya datang TELAT (belum ada saat node meng-emit) tetap
 *     tampil karena node tidak meng-emit ulang. Kita pantau pembuatan dokumen (ahyg)
 *     dan meng-emit-ulang node yang tadinya "unknown" agar tersaring begitu dokumen ada.
 *
 * DRY_RUN = true : hanya mencatat apa yang AKAN dibuang, tidak mengubah apa pun.
 */
object NodeFilter {
    private const val DRY_RUN = false

    @Volatile private var logCount = 0
    @Volatile private var normalLogged = 0
    @Volatile private var errCount = 0
    @Volatile private var describeCount = 0

    // Node yang seluruh anaknya game (atau sudah kosong); induknya ikut membuang node ini
    private val emptied: MutableSet<Any> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))

    // Node yang pernah memiliki anak dengan dokumen belum tersedia; kandidat bocor
    private val maybeLeaky: MutableSet<Any> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val rescanQueued = AtomicBoolean(false)

    private var zRef: Method? = null

    private fun log(msg: String) {
        if (logCount < 300) {
            logCount++
            XposedBridge.log("HG-node $msg")
        }
    }

    private fun isGame(doc: Any): Boolean = try {
        XposedHelpers.callMethod(doc, "I")
        true
    } catch (t: Throwable) {
        false
    }

    private fun describe(o: Any): String =
        o.javaClass.name + "{" +
            o.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                .take(8).joinToString(",") { it.name + ":" + it.type.simpleName } + "}"

    // Cari objek ahyg di dalam objek (maks 3 tingkat), termasuk AtomicReference dan array kecil
    private fun findDoc(o: Any?, depth: Int, docCls: Class<*>, seen: MutableMap<Any, Boolean>): Any? {
        if (o == null) return null
        if (docCls.isInstance(o)) return o
        if (depth >= 3 || seen.size > 120) return null
        if (o is AtomicReference<*>) return findDoc(o.get(), depth + 1, docCls, seen)
        if (o is Array<*>) {
            for (e in o.take(4)) {
                val r = findDoc(e, depth + 1, docCls, seen)
                if (r != null) return r
            }
            return null
        }
        val cn = o.javaClass.name
        if (o is CharSequence || o is Number || o is Boolean || o is Enum<*> ||
            cn.startsWith("java.") || cn.startsWith("android.") ||
            cn.startsWith("kotlin.") || cn.startsWith("j$.")) return null
        if (seen.put(o, true) != null) return null
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            val fs = c.declaredFields
            if (fs.size <= 40) {
                for (f in fs) {
                    if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                    val v = try {
                        f.isAccessible = true
                        f.get(o)
                    } catch (t: Throwable) {
                        null
                    }
                    val r = findDoc(v, depth + 1, docCls, seen)
                    if (r != null) return r
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun children(node: Any): List<Any?> {
        val arr = try {
            XposedHelpers.getObjectField(node, "i")
        } catch (t: Throwable) {
            null
        }
        return if (arr is Array<*>) arr.toList() else emptyList()
    }

    private fun process(param: XC_MethodHook.MethodHookParam, docCls: Class<*>) {
        val node = param.thisObject ?: return
        val result = param.result ?: return
        val list = XposedHelpers.getObjectField(result, "b") as? List<*> ?: return
        if (list.isEmpty()) return
        val kids = children(node)
        if (kids.isEmpty()) return

        val dropped = ArrayList<Any>()
        val ids = ArrayList<String>()
        var unknown = 0
        var cascaded = 0
        var present = 0
        for (kid in kids) {
            if (kid == null) continue
            present++
            if (emptied.contains(kid)) {
                val proto = runCatching { XposedHelpers.getObjectField(kid, "a") }.getOrNull()
                if (proto != null) {
                    dropped.add(proto)
                    cascaded++
                }
                continue
            }
            val w = runCatching { XposedHelpers.getObjectField(kid, "w") }.getOrNull()
            val doc = findDoc(w, 0, docCls, IdentityHashMap<Any, Boolean>())
            if (doc == null) {
                unknown++
                if (w != null && describeCount < 3) {
                    describeCount++
                    log("w tidak berisi dokumen: " + describe(w))
                }
                continue
            }
            if (isGame(doc)) {
                val proto = runCatching { XposedHelpers.getObjectField(kid, "a") }.getOrNull() ?: continue
                dropped.add(proto)
                ids.add(runCatching { XposedHelpers.callMethod(doc, "bR").toString() }.getOrDefault("?"))
            }
        }

        if (dropped.isEmpty()) {
            if (unknown > 0) {
                maybeLeaky.add(node)
                log("nodrop node=${System.identityHashCode(node)} size=${list.size} kids=${kids.size} present=$present unknown=$unknown")
            }
            return
        }

        val filtered = ArrayList<Any?>(list.size)
        var matched = 0
        for (e in list) {
            if (e != null && dropped.any { it === e || it == e }) matched++ else filtered.add(e)
        }
        val allGone = dropped.size >= present && matched > 0

        val anomaly = cascaded > 0 || matched != dropped.size || unknown > 0
        if (anomaly || normalLogged < 25) {
            if (!anomaly) normalLogged++
            log("node=${System.identityHashCode(node)} size=${list.size} kids=${kids.size} games=${ids.size} " +
                "cascade=$cascaded matched=$matched " +
                "unknown=$unknown allGone=$allGone -> ${filtered.size}" + (if (DRY_RUN) " (dry-run)" else ""))
        }
        if (unknown > 0) maybeLeaky.add(node)

        if (DRY_RUN) return
        if (matched > 0) {
            XposedHelpers.setObjectField(result, "b", filtered)
            // matikan hasMore tiap ada item dibuang, agar UI tidak menyisakan
            // indikator "memuat lagi" untuk slot game yang sudah hilang
            runCatching { XposedHelpers.setBooleanField(result, "a", false) }
        }
        if (allGone) {
            emptied.add(node)
        }
    }

    // Emit ulang sebuah node: panggil z(null) lalu masukkan hasilnya ke state flow-nya.
    private fun reemit(node: Any) {
        val z = zRef ?: return
        val bgcd = z.invoke(node, null)
        val v = XposedHelpers.getObjectField(node, "v")
        XposedHelpers.callMethod(v, "e", bgcd)
    }

    // Saring ulang node-node yang tadinya punya anak tanpa dokumen; dipakai saat dokumen telat tiba.
    private fun rescan() {
        val nodes = try { maybeLeaky.toList() } catch (t: Throwable) { emptyList() }
        var n = 0
        for (node in nodes) {
            try {
                reemit(node)
                n++
            } catch (t: Throwable) {
                // node mungkin sudah tidak valid; abaikan
            }
        }
        if (n > 0) log("rescan reemit=$n kandidat=${nodes.size}")
    }

    private fun scheduleRescan() {
        if (rescanQueued.getAndSet(true)) return
        mainHandler.postDelayed({
            rescanQueued.set(false)
            try {
                rescan()
            } catch (t: Throwable) {
                if (errCount < 5) {
                    errCount++
                    XposedBridge.log("HG-node rescan error: $t")
                }
            }
        }, 300L)
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val nodeCls = runCatching { XposedHelpers.findClass("bckc", cl) }.getOrNull()
        val docCls = runCatching { XposedHelpers.findClass("ahyg", cl) }.getOrNull()
        if (nodeCls == null || docCls == null) {
            XposedBridge.log("HG-node: kelas bckc atau ahyg tidak ditemukan (versi Play Store berubah?)")
            return
        }
        val zMethod = nodeCls.declaredMethods.firstOrNull {
            it.name == "z" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Throwable::class.java
        }
        if (zMethod == null) {
            XposedBridge.log("HG-node: method bckc.z(Throwable) tidak ditemukan")
            return
        }
        zMethod.isAccessible = true
        zRef = zMethod

        XposedBridge.hookMethod(zMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    process(param, docCls)
                } catch (t: Throwable) {
                    if (errCount < 5) {
                        errCount++
                        XposedBridge.log("HG-node error: $t")
                    }
                }
            }
        })

        // Pantau pembuatan dokumen baru: begitu dokumen (yang mungkin telat) dibuat,
        // jadwalkan penyaringan ulang node yang tadinya belum punya dokumen.
        var ctorCount = 0
        for (ctor in docCls.declaredConstructors) {
            try {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            scheduleRescan()
                        } catch (t: Throwable) {
                            // abaikan
                        }
                    }
                })
                ctorCount++
            } catch (t: Throwable) {
                // konstruktor tertentu mungkin tak bisa di-hook; abaikan
            }
        }

        XposedBridge.log("HG-node: terpasang pada bckc.z (DRY_RUN=$DRY_RUN) + $ctorCount ctor ahyg")
    }
}
