package com.example.hidegames

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

class MainHook : IXposedHookLoadPackage {
    // Ubah ke true kalau callback klik ternyata menerima indeks Int
    private val translateIndex = true
    private val hideType = "GAMES"

    @Volatile private var lastRemoved = -1
    private val cache: MutableMap<Any, Any> = Collections.synchronizedMap(WeakHashMap<Any, Any>())

    private fun wrap(orig: Any, iface: Class<*>): Any = cache.getOrPut(orig) {
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), InvocationHandler { self, m, args ->
            if (m.declaringClass == Any::class.java) {
                when (m.name) {
                    "equals" -> self === args?.get(0)
                    "hashCode" -> System.identityHashCode(self)
                    else -> orig.toString()
                }
            } else {
                val callArgs: Array<Any?> = args?.copyOf() ?: arrayOf()
                XposedBridge.log("HideGames: klik ${m.name} args=" + callArgs.joinToString { "${it?.javaClass?.simpleName}=$it" })
                if (translateIndex && callArgs.isNotEmpty()) {
                    val a0 = callArgs[0]
                    val r = lastRemoved
                    if (a0 is Int && r >= 0 && a0 >= r) callArgs[0] = a0 + 1
                }
                try {
                    m.invoke(orig, *callArgs)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        })
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.vending") return
        val cl = lpparam.classLoader
        NodeFilter.install(lpparam)

        val navCls: Class<*>
        val ifaceCls: Class<*>
        try {
            navCls = XposedHelpers.findClass("aoei", cl)
            ifaceCls = XposedHelpers.findField(XposedHelpers.findClass("aoem", cl), "a").type
        } catch (t: Throwable) {
            XposedBridge.log("HideGames: kelas tidak ditemukan (versi Play Store berubah?): $t")
            return
        }

        XposedBridge.hookAllConstructors(navCls, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val items = param.args[0] as? List<*> ?: return
                    val idx = items.indexOfFirst {
                        it != null && XposedHelpers.getObjectField(it, "c").toString() == hideType
                    }
                    if (idx < 0) return
                    lastRemoved = idx

                    val sel = param.args[1] as Int
                    val newSel = when {
                        sel > idx -> sel - 1
                        sel == idx -> 0
                        else -> sel
                    }
                    param.args[0] = items.filterIndexed { i, _ -> i != idx }
                    param.args[1] = newSel

                    val action = param.args[5]
                    if (action != null && ifaceCls.isInterface) {
                        val cur = XposedHelpers.getObjectField(action, "a")
                        if (cur != null && !Proxy.isProxyClass(cur.javaClass)) {
                            XposedHelpers.setObjectField(action, "a", wrap(cur, ifaceCls))
                        }
                    }
                    XposedBridge.log("HideGames: tab $hideType dibuang idx=$idx selected $sel -> $newSel")
                } catch (t: Throwable) {
                    XposedBridge.log("HideGames: hook gagal: $t")
                }
            }
        })
    }
}
