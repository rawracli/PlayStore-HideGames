package com.example.hidegames

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

object GameFilter {
    @Volatile private var diag = 0
    @Volatile private var removed = 0

    // 1 = game, 0 = bukan game, -1 = dokumen tidak ditemukan di elemen
    private val verdict: MutableMap<Any, Int> = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    private fun isGame(doc: Any): Boolean = try {
        XposedHelpers.callMethod(doc, "I")
        true
    } catch (t: Throwable) {
        false
    }

    private fun findDoc(o: Any?, depth: Int, docCls: Class<*>, seen: MutableMap<Any, Boolean>): Any? {
        if (o == null) return null
        if (docCls.isInstance(o)) return o
        if (depth >= 2 || seen.size > 120) return null
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

    private fun classify(el: Any, docCls: Class<*>): Int {
        verdict[el]?.let { return it }
        val doc = findDoc(el, 0, docCls, IdentityHashMap<Any, Boolean>())
        val v = if (doc == null) -1 else if (isGame(doc)) 1 else 0
        verdict[el] = v
        if (v == 1 && removed < 40) {
            val id = try { XposedHelpers.callMethod(doc, "bR").toString() } catch (t: Throwable) { "?" }
            XposedBridge.log("HG-filter GAME id=$id")
        }
        return v
    }

    private fun scan(list: MutableList<Any?>, docCls: Class<*>) {
        var i = list.size - 1
        while (i >= 0) {
            val el = list[i]
            if (el != null) {
                val v = classify(el, docCls)
                if (v == 1) {
                    list.removeAt(i)
                    removed++
                } else if (v == -1 && diag < 3) {
                    diag++
                    XposedBridge.log("HG-filter dokumen tidak ditemukan di ${el.javaClass.name}; fields=" +
                        el.javaClass.declaredFields.joinToString(",") { it.name + ":" + it.type.simpleName })
                }
            }
            i--
        }
    }

    private fun handle(param: XC_MethodHook.MethodHookParam, docCls: Class<*>, where: String) {
        try {
            @Suppress("UNCHECKED_CAST")
            val list = param.args[6] as? MutableList<Any?> ?: return
            scan(list, docCls)
        } catch (t: Throwable) {
            if (diag < 6) {
                diag++
                XposedBridge.log("HG-filter error ($where): $t")
            }
        }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val docCls = runCatching { XposedHelpers.findClass("ahyg", cl) }.getOrNull()
        val cix = runCatching { XposedHelpers.findClass("bcix", cl) }.getOrNull()
        if (docCls == null || cix == null) {
            XposedBridge.log("HG-filter: kelas ahyg atau bcix tidak ditemukan (versi Play Store berubah?)")
            return
        }
        val target = cix.declaredMethods.firstOrNull { m ->
            m.name == "d" && m.parameterTypes.size == 12 &&
                m.parameterTypes[0] == String::class.java &&
                List::class.java.isAssignableFrom(m.parameterTypes[6])
        }
        if (target == null) {
            XposedBridge.log("HG-filter: method bcix.d(12 parameter) tidak ditemukan")
            return
        }
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                handle(param, docCls, "sebelum")
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                handle(param, docCls, "sesudah")
            }
        })
        XposedBridge.log("HG-filter: terpasang pada bcix.d")
    }
}
