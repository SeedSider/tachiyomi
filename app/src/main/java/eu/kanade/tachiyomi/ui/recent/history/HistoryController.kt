package eu.kanade.tachiyomi.ui.recent.history

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupRestoreService
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.databinding.HistoryControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.*
import eu.kanade.tachiyomi.ui.browse.source.browse.ProgressItem
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.lang.dayFormat
import eu.kanade.tachiyomi.util.lang.endFormat
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.appcompat.queryTextChanges
import uy.kohesive.injekt.injectLazy
import java.util.*

/**
 * Fragment that shows recently read manga.
 * Uses [R.layout.history_controller].
 * UI related actions should be called from here.
 */
class HistoryController :
    NucleusController<HistoryControllerBinding, HistoryPresenter>(),
    RootController,
    NoToolbarElevationController,
    FlexibleAdapter.OnUpdateListener,
    FlexibleAdapter.EndlessScrollListener,
    HistoryAdapter.OnRemoveClickListener,
    HistoryAdapter.OnResumeClickListener,
    HistoryAdapter.OnItemClickListener,
    RemoveHistoryDialog.Listener {

    private val db: DatabaseHelper by injectLazy()
    /**
     * Adapter containing the recent manga.
     */
    var adapter: HistoryAdapter? = null
        private set

    /**
     * Endless loading item.
     */
    private var progressItem: ProgressItem? = null

    /**
     * Search query.
     */
    private var query = ""

    override fun getTitle(): String? {
        return resources?.getString(R.string.label_recent_manga)
    }

    override fun createPresenter(): HistoryPresenter {
        return HistoryPresenter()
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        binding = HistoryControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        // Initialize adapter
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        adapter = HistoryAdapter(this@HistoryController)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.adapter = adapter
        adapter?.fastScroller = binding.fastScroller
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    /**
     * Populate adapter with chapters
     *
     * @param mangaHistory list of manga history
     */
    fun onNextManga(mangaHistory: List<HistoryItem>, cleanBatch: Boolean = false) {
        if (adapter?.itemCount ?: 0 == 0 || cleanBatch) {
            resetProgressItem()
        }
        if (cleanBatch) {
            adapter?.updateDataSet(mangaHistory)
        } else {
            adapter?.onLoadMoreComplete(mangaHistory)
        }
    }

    /**
     * Safely error if next page load fails
     */
    fun onAddPageError(error: Throwable) {
        adapter?.onLoadMoreComplete(null)
        adapter?.endlessTargetCount = 1
    }

    override fun onUpdateEmptyView(size: Int) {
        if (size > 0) {
            binding.emptyView.hide()
        } else {
            binding.emptyView.show(R.string.information_no_recent_manga)
        }
    }

    /**
     * Sets a new progress item and reenables the scroll listener.
     */
    private fun resetProgressItem() {
        progressItem = ProgressItem()
        adapter?.endlessTargetCount = 0
        adapter?.setEndlessScrollListener(this, progressItem!!)
    }

    override fun onLoadMore(lastPosition: Int, currentPage: Int) {
        val view = view ?: return
        if (BackupRestoreService.isRunning(view.context.applicationContext)) {
            onAddPageError(Throwable())
            return
        }
        val adapter = adapter ?: return
        presenter.requestNext(adapter.itemCount, query)
    }

    override fun noMoreLoad(newItemsSize: Int) {}

    override fun onResumeClick(position: Int) {
        val activity = activity ?: return
        val (manga, chapter, _) = (adapter?.getItem(position) as? HistoryItem)?.mch ?: return

        val nextChapter = presenter.getNextChapter(chapter, manga)
        if (nextChapter != null) {
            val intent = ReaderActivity.newIntent(activity, manga, nextChapter)
            startActivity(intent)
        } else {
            activity.toast(R.string.no_next_chapter)
        }
    }

    override fun onRemoveClick(position: Int) {
        val (manga, _, history) = (adapter?.getItem(position) as? HistoryItem)?.mch ?: return
        RemoveHistoryDialog(this, manga, history).showDialog(router)
    }

    override fun onItemClick(position: Int) {
        val manga = (adapter?.getItem(position) as? HistoryItem)?.mch?.manga ?: return
        router.pushController(MangaController(manga).withFadeTransaction())
    }

    override fun removeHistory(manga: Manga, history: History, all: Boolean) {
        if (all) {
            // Reset last read of chapter to 0L
            presenter.removeAllFromHistory(manga.id!!)
        } else {
            // Remove all chapters belonging to manga from library
            presenter.removeFromHistory(history)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.history, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()
        }
        searchView.queryTextChanges()
            .filter { router.backstack.lastOrNull()?.controller() == this }
            .onEach {
                query = it.toString()
                presenter.updateList(query)
            }
            .launchIn(scope)

        // Fixes problem with the overflow icon showing up in lieu of search
        searchItem.fixExpand(
            onExpand = { invalidateMenuOnExpand() }
        )

        val historyButton = menu.findItem(R.id.action_delete)
        historyButton.actionView
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_delete -> showDeleteDialog()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showDeleteDialog() {
        val deleteDialog = AlertDialog.Builder(view?.context)
        deleteDialog.setTitle("Delete History")
        val items = arrayOf("Today", "2 Days", "3 Days", "All")
        var checkedItem = 0
        deleteDialog.setSingleChoiceItems(items, checkedItem) { _, which ->
            when (which) {
                0 -> checkedItem = 0
                1 -> checkedItem = 1
                2 -> checkedItem = 2
                3 -> checkedItem = 3
            }
        }
        deleteDialog.setPositiveButton(android.R.string.ok) { _, _ ->
            deleteHistory(checkedItem)
        }
        deleteDialog.setNegativeButton(android.R.string.cancel) { _, _ ->
        }
        val delete = deleteDialog.create()
        delete.setCanceledOnTouchOutside(false)
        delete.show()
        delete.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK)
        delete.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK)
    }

    private fun deleteHistory(item: Int) {
        val from: Date
        val to = endFormat()
        when (item) {
            0 -> {
                from = dayFormat(0)
                clearHistory(from.time, to.time)
            }
            1 -> {
                from = dayFormat(-1)
                clearHistory(from.time, to.time)
            }
            2 -> {
                from = dayFormat(-2)
                clearHistory(from.time, to.time)
            }
            else -> {
                val ctrl = ClearAllHistoryDialogController()
                ctrl.targetController = this@HistoryController
                ctrl.showDialog(router)
            }
        }
    }

    class ClearAllHistoryDialogController : DialogController() {
        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog(activity!!)
                .message(R.string.clear_history_confirmation)
                .positiveButton(android.R.string.ok) {
                    (targetController as? HistoryController)?.clearAllHistory()
                }
                .negativeButton(android.R.string.cancel)
        }
    }

    private fun clearAllHistory() {
        db.deleteHistory().executeAsBlocking()
        activity?.toast(R.string.clear_history_completed)
    }

    private fun clearHistory(from: Long, to: Long) {
        db.deleteHistoryFunc(from, to).executeAsBlocking()
        activity?.toast(R.string.clear_history_completed)
    }
}
