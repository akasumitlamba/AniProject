package app.anihome.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.R
import app.anihome.launcher.databinding.ActivityHistoryBinding
import app.anihome.launcher.databinding.ItemLogBinding
import app.anihome.launcher.tracking.WatchRepository
import app.anihome.launcher.web.WebActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Full local watch log for one anime: date, time, site, episode number. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var repo: WatchRepository
    private val adapter = Adapter()
    private val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WallpaperSupport.apply(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = WatchRepository(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        intent.getStringExtra(EXTRA_TITLE)?.let { binding.heading.text = it }
        load()
    }

    private fun load() {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return finish()
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { repo.history(key) }
            adapter.items = items
            adapter.notifyDataSetChanged()
            binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<WatchRepository.WatchLogEntry> = emptyList()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    }

    private inner class Holder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WatchRepository.WatchLogEntry) {
            binding.episode.text = getString(R.string.history_episode, item.episode)
            binding.meta.text = getString(R.string.history_meta, dateTime.format(Date(item.watchedAt)), item.host)
            binding.root.setOnClickListener {
                item.url?.let { startActivity(WebActivity.intent(this@HistoryActivity, it)) }
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "anime_key"
        const val EXTRA_TITLE = "anime_title"
    }
}
