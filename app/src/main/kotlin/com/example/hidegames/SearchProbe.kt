package com.example.hidegames

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

object SearchProbe {
    private var counter = 0
    private val noise = listOf(
        "com.example.hidegames", "Vector", "XposedBridge", "HookBridge",
        "java.lang.reflect", "r8-map-id"
    )

    private fun collect(o: Any?, depth: Int, seen: MutableSet<Int>, out: MutableList<String>, path: String) {
        if (o == null || depth > 5 || out.size >= 60) return
        when (o) {
            is String -> if (o.length in 2..100) out.add("$path=$o")
            is Number, is Boolean, is Enum<*> -> out.add("$path=$o")
            is ByteArray -> return
            is Collection<*> -> o.take(8).forEachIndexed { i, e -> collect(e, depth + 1, seen, out, "$path[$i]") }
            is Iterable<*> -> {
                val ts = runCatching { o.toString() }.getOrNull() ?: return
                if (ts.startsWith("<ByteString")) out.add("$path=" + ts.take(140))
            }
            else -> {
                val cn = o.javaClass.name
                if (cn.startsWith("java.") || cn.startsWith("android.") ||
                    cn.startsWith("kotlin.") || cn.startsWith("androidx.")) return
                if (!seen.add(System.identityHashCode(o))) return
                var c: Class<*>? = o.javaClass
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        if (Modifier.isStatic(f.modifiers)) continue
                        val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                        collect(v, depth + 1, seen, out, path + "." + f.name)
                    }
                    c = c.superclass
                }
            }
        }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = try {
            XposedHelpers.findClass("bbaw", lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("HG-probe: bbaw tidak ditemukan: $t")
            return
        }
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    counter++
                    val n = counter
                    if (n > 30) return
                    val o = param.thisObject
                    for (f in cls.declaredFields) {
                        if (Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val v = f.get(o)
                        val out = mutableListOf<String>()
                        collect(v, 0, HashSet<Int>(), out, f.name + "(" + (v?.javaClass?.name ?: "null") + ")")
                        out.joinToString(" | ").chunked(1400).take(2).forEachIndexed { i, s ->
                            XposedBridge.log("HG-card #$n ${f.name}.$i $s")
                        }
                    }
                    if (n <= 2) {
                        val from = Throwable().stackTrace.map { it.toString() }
                            .filter { fr -> noise.none { fr.contains(it) } }
                            .take(10).joinToString(" <- ")
                        XposedBridge.log("HG-from #$n $from")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("HG-probe error: $t")
                }
            }
        })
    }
}
