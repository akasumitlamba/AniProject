package app.anihome.launcher.controls

import android.content.AttributionSource
import android.os.*
import moe.shizuku.api.BinderContainer
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/** Restore only Shizuku's broker connection, using the already-authorized running server. */
internal object ShizukuBroker {
    private const val authority = "moe.shizuku.privileged.api.shizuku"
    private fun methods(type: Class<*>): List<Method> =
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.getDeclaredMethods(type).filterIsInstance<Method>()
        else type.declaredMethods.toList()
    private fun invoke(target: Any?, method: Method, vararg args: Any?): Any? {
        method.isAccessible = true
        return method.invoke(target, *args)
    }
    // Pinned Shizuku 13.1.5 broker wire format uses its internal BinderContainer parcelable.
    @android.annotation.SuppressLint("RestrictedApi")
    fun refresh() {
        if (!Shizuku.pingBinder()) return
        val stub = Class.forName("android.app.IActivityManager\$Stub")
        val activityManager = invoke(null, methods(stub).first { it.name == "asInterface" },
            ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity"))) ?: return
        val operations = methods(Class.forName("android.app.IActivityManager"))
        val acquire = operations.first { it.name == "getContentProviderExternal" }
        val user = Process.myUid() / 100000
        val holder = if (acquire.parameterTypes.size == 4) invoke(activityManager, acquire, authority, user, null, authority)
            else invoke(activityManager, acquire, authority, user, null)
        if (holder == null) return
        try {
            val fields = if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.getInstanceFields(holder.javaClass) else holder.javaClass.declaredFields.toList()
            val field = fields.first { it.name == "provider" }.apply { isAccessible = true }
            val providerBinder = (field.get(holder) as IInterface).asBinder()
            val native = Class.forName("android.content.ContentProviderNative")
            val provider = invoke(null, methods(native).first { it.name == "asInterface" }, ShizukuBinderWrapper(providerBinder)) ?: return
            val call = methods(Class.forName("android.content.IContentProvider")).filter { it.name == "call" }.maxBy { it.parameterTypes.size }
            val extra = Bundle().apply { putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", BinderContainer(Shizuku.getBinder())) }
            when {
                Build.VERSION.SDK_INT >= 31 && call.parameterTypes.first().name == "android.content.AttributionSource" -> {
                    val source = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
                    invoke(provider, call, source, authority, "sendBinder", null, extra)
                }
                call.parameterTypes.size == 6 -> invoke(provider, call, "com.android.shell", null, authority, "sendBinder", null, extra)
                call.parameterTypes.size == 5 -> invoke(provider, call, "com.android.shell", authority, "sendBinder", null, extra)
                call.parameterTypes.size == 4 -> invoke(provider, call, "com.android.shell", "sendBinder", null, extra)
            }
        } finally {
            val release = operations.firstOrNull { it.name == "removeContentProviderExternalAsUser" }
                ?: operations.firstOrNull { it.name == "removeContentProviderExternal" }
            if (release != null) runCatching {
                if (release.parameterTypes.size == 3) invoke(activityManager, release, authority, null, user)
                else invoke(activityManager, release, authority, null)
            }
        }
    }
}
