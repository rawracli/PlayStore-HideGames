package com.example.hidegames

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Menyembunyikan game pada daftar (search/feed).
 *
 * Jalur render: dokumen `cjyo` dikumpulkan ke sebuah array lalu dirender serentak
 * oleh `ajmv.a(cjyp, cjyo[], cjnt, cjtu, cjoj, cjmk)`. Panjang array menentukan
 * arity dari lambda `cjoj` (mis. `azhr`/`axip`) yang dibuat pemanggil, jadi array
 * TIDAK BOLEH dipendekkan (menyebabkan ArrayIndexOutOfBounds).
 *
 * Solusi: ganti dokumen game dengan dokumen "kosong" (Proxy `cjyo`) yang:
 *  - `pd()` langsung selesai (tidak pernah sama dengan COROUTINE_SUSPENDED), dan
 *  - tidak pernah mem-publish konten ke continuation `ajmt`.
 * Panjang array tetap, sehingga arity tetap cocok; slot game menjadi kosong.
 *
 * Deteksi game: telusuri `cjyo` (umumnya `bjmg` pembungkus `ahyg`) lalu panggil
 * `ahyg.I()`; sukses = game, throw = bukan game.
 */
object GameFilter {
    @Volatile private var diag = 0
    @Volatile private var replaced = 0

    // 1 = game, 0 = bukan game, -1 = dokumen tidak ditemukan
    private val verdict: MutableMap<Any, Int> = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    private fun isGame(doc: Any): Boolean = try {
        XposedHelpers.callMethod(doc, "I")
        true
    } catch (t: Throwable) {
        false
    }

    private fun idOf(doc: Any): String = try {
        XposedHelpers.callMethod(doc, "bR").toString()
    } catch (t: Throwable) {
        "?"
    }

    private fun findDoc(o: Any?, depth: Int, docCls: Class<*>, seen: MutableMap<Any, Boolean>): Any? {
        if (o == null) return null
        if (docCls.isInstance(o)) return o
        if (depth >= 3 || seen.size > 200) return null
        val cn = o.javaClass.name
        if (o is CharSequence || o is Number || o is Boolean || o is Enum<*> ||
            cn.startsWith("java.") || cn.startsWith("android.") ||
            cn.startsWith("kotlin.") || cn.startsWith("j$.")) return null
        if (seen.put(o, true) != null) return null
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            val fs = c.declaredFields
            if (fs.size <= 64) {
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

    private fun isGameDoc(el: Any, docCls: Class<*>): Boolean {
        verdict[el]?.let { return it == 1 }
        val doc = findDoc(el, 0, docCls, IdentityHashMap<Any, Boolean>())
        val v = if (doc == null) -1 else if (isGame(doc)) 1 else 0
        verdict[el] = v
        if (v == 1 && replaced < 40) {
            XposedBridge.log("HG-filter GAME ditemukan id=${idOf(doc!!)}")
        }
        return v == 1
    }

    private fun makeEmptyDoc(cl: ClassLoader, cjyoCls: Class<*>): Any =
        Proxy.newProxyInstance(cl, arrayOf(cjyoCls)) { proxy, method, args ->
            when (method.name) {
                "pd" -> null
                "equals" -> proxy === (args?.getOrNull(0))
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "HG-empty-doc"
                else -> null
            }
        }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val docCls = runCatching { XposedHelpers.findClass("ahyg", cl) }.getOrNull()
        val cjyoCls = runCatching { XposedHelpers.findClass("cjyo", cl) }.getOrNull()
        val mv = runCatching { XposedHelpers.findClass("ajmv", cl) }.getOrNull()
        if (docCls == null || cjyoCls == null || mv == null) {
            XposedBridge.log("HG-filter: kelas ahyg/cjyo/ajmv tidak ditemukan (versi Play Store berubah?)")
            return
        }
        val emptyDoc = makeEmptyDoc(cl, cjyoCls)
        val target = mv.declaredMethods.firstOrNull { m ->
            m.name == "a" && Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 6 && m.parameterTypes[1].isArray
        }
        if (target == null) {
            XposedBridge.log("HG-filter: method ajmv.a(cjyo[]) tidak ditemukan")
            return
        }
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val arr = param.args[1] as? Array<Any?> ?: return
                    var changed = false
                    for (i in arr.indices) {
                        val el = arr[i]
                        if (el == null) {
                            arr[i] = emptyDoc
                            changed = true
                            continue
                        }
                        if (isGameDoc(el, docCls)) {
                            arr[i] = emptyDoc
                            replaced++
                            changed = true
                        }
                    }
                    if (changed && diag < 20) {
                        diag++
                        XposedBridge.log("HG-filter: ganti dokumen game -> kosong (len=${arr.size})")
                    }
                } catch (t: Throwable) {
                    if (diag < 8) {
                        diag++
                        XposedBridge.log("HG-filter error: $t")
                    }
                }
            }
        })
        XposedBridge.log("HG-filter: terpasang pada ajmv.a (ganti dokumen game dgn kosong)")
    }
}
