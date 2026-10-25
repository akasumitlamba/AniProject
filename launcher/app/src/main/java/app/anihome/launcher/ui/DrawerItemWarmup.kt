package app.anihome.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.R
import app.anihome.launcher.databinding.ItemAppBinding
import java.util.ArrayDeque

/** Unbound item views use an application theme context and never retain an Activity/listener. */
internal object DrawerItemWarmup {
    private val handler = Handler(Looper.getMainLooper())
    private val pool = ArrayDeque<ItemAppBinding>()
    private var inflater: LayoutInflater? = null
    private var configuration = 0
    private val pump = object : Runnable {
        override fun run() {
            val factory = inflater ?: return
            repeat(2) { if (pool.size < 32) pool.addLast(ItemAppBinding.inflate(factory)) }
            if (pool.size < 32) handler.postDelayed(this, 32)
        }
    }
    fun warm(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val key = context.resources.configuration.hashCode()
        if (key != configuration) { pool.clear(); configuration = key }
        inflater = LayoutInflater.from(ContextThemeWrapper(context.applicationContext, R.style.Theme_AniHome_Drawer))
        handler.removeCallbacks(pump)
        if (pool.size < 32) handler.postDelayed(pump, 80)
    }
    fun pause() { handler.removeCallbacks(pump) }
    fun take(parent: ViewGroup): ItemAppBinding {
        val item = if (pool.isEmpty()) ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false) else pool.removeFirst()
        if (item.root.layoutParams == null) item.root.layoutParams = RecyclerView.LayoutParams(-1, -2)
        return item
    }
}
