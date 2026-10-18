package app.anihome.launcher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.anihome.launcher.R
import app.anihome.launcher.databinding.ItemReminderBinding
import app.anihome.launcher.tracking.WatchRepository

/** Home reminder chips. Tap opens the watch log; the X dismisses until a different episode is watched. */
class ReminderAdapter(
    private val onOpenLog: (WatchRepository.AnimeSummary) -> Unit,
    private val onDismiss: (WatchRepository.AnimeSummary) -> Unit,
) : ListAdapter<WatchRepository.AnimeSummary, ReminderAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemReminderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemReminderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WatchRepository.AnimeSummary) {
            binding.message.text = binding.root.context.getString(R.string.reminder_body, item.lastEpisode, item.title)
            binding.body.setOnClickListener { onOpenLog(item) }
            binding.root.setOnClickListener { onOpenLog(item) }
            binding.btnDismiss.setOnClickListener { onDismiss(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<WatchRepository.AnimeSummary>() {
        override fun areItemsTheSame(oldItem: WatchRepository.AnimeSummary, newItem: WatchRepository.AnimeSummary) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: WatchRepository.AnimeSummary, newItem: WatchRepository.AnimeSummary) = oldItem == newItem
    }
}
