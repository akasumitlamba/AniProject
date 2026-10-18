package app.anihome.launcher.ui

import android.content.Intent
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
import app.anihome.launcher.databinding.ActivityLibraryBinding
import app.anihome.launcher.databinding.ItemAnimeBinding
import app.anihome.launcher.tracking.WatchRepository
import app.anihome.launcher.web.WebActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the tracker knows, per anime and per website, with forget/mute controls. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private lateinit var repo: WatchRepository
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WallpaperSupport.apply(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
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
        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.library_clear_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.library_clear_all) { _, _ -> lifecycleScope.launch(Dispatchers.IO) { repo.clearAll(); load() } }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { repo.summaries() }
            adapter.items = items
            adapter.notifyDataSetChanged()
            binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun options(item: WatchRepository.AnimeSummary) {
        val muteLabel = if (item.muted) "Turn reminders on" else getString(R.string.action_dont_remind)
        val actions = listOfNotNull(
            getString(R.string.action_watch_log) to {
                startActivity(
                    Intent(this, HistoryActivity::class.java)
                        .putExtra(HistoryActivity.EXTRA_KEY, item.key)
                        .putExtra(HistoryActivity.EXTRA_TITLE, item.title),
                )
            },
            item.lastUrl?.let { getString(R.string.action_open) to { startActivity(WebActivity.intent(this, it)) } },
            muteLabel to { lifecycleScope.launch(Dispatchers.IO) { repo.setMuted(item.key, !item.muted); load() }; Unit },
            getString(R.string.action_forget) to {
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.library_forget_confirm, item.title))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_forget) { _, _ -> lifecycleScope.launch(Dispatchers.IO) { repo.forget(item.key); load() } }
                    .show()
                Unit
            },
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<WatchRepository.AnimeSummary> = emptyList()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemAnimeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    }

    private inner class Holder(private val binding: ItemAnimeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WatchRepository.AnimeSummary) {
            binding.title.text = if (item.muted) item.title + "  ·  " + getString(R.string.library_muted) else item.title
            binding.progress.text = getString(R.string.library_episode_line, item.lastEpisode, item.maxEpisode) +
                if (item.episodesWatched > 0) "  ·  ${item.episodesWatched} logged" else ""
            binding.sites.text = item.sites.joinToString("  ·  ") { "${it.host}: ep ${it.maxEpisode}" }
            binding.root.setOnClickListener { options(item) }
        }
    }
}
