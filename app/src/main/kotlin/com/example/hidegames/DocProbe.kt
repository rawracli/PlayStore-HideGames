package com.example.hidegames

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections

object DocProbe {
    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val sigs: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val counts: MutableMap<String, Int> = Collections.synchronizedMap(HashMap<String, Int>())
    private val noise = listOf(
        "com.example.hidegames", "Vector", "XposedBridge", "HookBridge",
        "java.lang.reflect", "r8-map-id"
    )
    private val watch = listOf("bcix", "bcjm", "aiac", "ahhu")

    private fun call(o: Any, name: String): String = try {
        val r = XposedHelpers.callMethod(o, name)
        (r?.toString() ?: "null").replace("\n", " ").take(120)
    } catch (t: Throwable) {
        "ERR:" + (t.cause ?: t).javaClass.simpleName
    }

    private fun brief(v: Any?): String {
        if (v == null) return "null"
        return when (v) {
            is String -> "String(" + v.take(40) + ")"
            is Number, is Boolean -> v.toString()
            is Collection<*> -> "Collection(size=${v.size}, first=${v.firstOrNull()?.javaClass?.name}, hasDoc=${v.any { it != null && it.javaClass.name == "ahyg" }})"
            is Array<*> -> "Array(size=${v.size}, first=${v.firstOrNull()?.javaClass?.name})"
            else -> v.javaClass.name
        }
    }

    private fun frames(): List<String> =
        Throwable().stackTrace.map { it.toString() }
            .filter { f -> noise.none { f.contains(it) } }

    private fun probeClass(name: String, cl: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(name, cl)
        } catch (t: Throwable) {
            XposedBridge.log("HG-m: kelas $name tidak ditemukan")
            return
        }
        for (m in cls.declaredMethods) {
            if (m.isBridge) continue
            try {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val sig = name + "." + m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                            val args = param.args.joinToString(" | ") { brief(it) }
                            val res = brief(param.result)
                            val interesting = (args + res).let { it.contains("Collection(") || it.contains("Array(") || it.contains("ahyg") }
                            if (!interesting) return
                            val n = synchronized(counts) {
                                val c = (counts[sig] ?: 0) + 1
                                counts[sig] = c
                                c
                            }
                            if (n > 15) return
                            XposedBridge.log("HG-m $sig -> ${m.returnType.simpleName} #$n args=[$args] result=$res")
                        } catch (t: Throwable) {
                        }
                    }
                })
            } catch (t: Throwable) {
            }
        }
        XposedBridge.log("HG-m: probe terpasang pada $name")
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = try {
            XposedHelpers.findClass("ahyg", lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("HG-doc: ahyg tidak ditemukan: $t")
            return
        }
        XposedBridge.log("HG-doc: probe terpasang")
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val d = param.thisObject
                    val id = call(d, "bR")
                    if (seen.size >= 150 || !seen.add(id)) return
                    val game = !call(d, "I").startsWith("ERR:")
                    XposedBridge.log("HG-doc " + (if (game) "GAME" else "app ") + " id=$id title=${call(d, "bZ")}")
                    if (game) {
                        val fr = frames()
                        val sig = fr.take(8).joinToString(" <- ")
                        if (sigs.size < 8 && sigs.add(sig)) {
                            XposedBridge.log("HG-game-from #${sigs.size} " + fr.take(16).joinToString(" <- "))
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("HG-doc error: $t")
                }
            }
        })
        for (n in watch) probeClass(n, lpparam.classLoader)
    }
}
