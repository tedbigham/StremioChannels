package com.tedbigham.stremiochannels

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var rows: LinearLayout
    private lateinit var emptyState: TextView
    private var configs: List<ChannelConfigStore.ChannelConfig> = emptyList()
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Channel editor opened; scheduling TMDB refresh work")
        TmdbRefreshScheduler.schedulePeriodicRefresh(this)
        renderEditor()
    }

    private fun renderEditor() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(56, 40, 56, 40)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher_user_foreground)
                adjustViewBounds = true
            },
            LinearLayout.LayoutParams(72, 72).apply { rightMargin = 20 }
        )
        header.addView(
            TextView(this).apply {
                text = "Stremio Channels"
                setTextColor(Color.WHITE)
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }

        val listPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        emptyState = TextView(this).apply {
            text = "No custom channels yet"
            setTextColor(COLOR_SECONDARY_TEXT)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        listPane.addView(emptyState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 96))
        listPane.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(listPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 0, 0, 0)
        }
        actions.addView(actionButton("Create") { showChannelDialog(null) })
        actions.addView(actionButton("Delete") { selectedConfig()?.let { deleteSelected(it) } })
        actions.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actions.addView(actionButton("About") { showAboutDialog() })
        content.addView(actions, LinearLayout.LayoutParams(320, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reloadConfigs()
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 22f
            isAllCaps = false
            isFocusable = true
            includeFontPadding = true
            minHeight = 88
            setPadding(20, 8, 20, 8)
            setOnFocusChangeListener { view, hasFocus -> view.background = buttonBackground(hasFocus) }
            background = buttonBackground(false)
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 92).apply {
                bottomMargin = 20
            }
        }

    private fun reloadConfigs() {
        configs = ChannelConfigStore.loadCustomChannels(this)
        selectedIndex = selectedIndex.coerceIn(0, (configs.size - 1).coerceAtLeast(0))
        rows.removeAllViews()
        emptyState.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE

        configs.forEachIndexed { index, config ->
            rows.addView(channelRow(index, config))
        }

        rows.post {
            if (configs.isNotEmpty()) {
                rows.getChildAt(selectedIndex)?.requestFocus()
            }
        }
    }

    private fun channelRow(index: Int, config: ChannelConfigStore.ChannelConfig): TextView =
        TextView(this).apply {
            text = config.displayName
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            includeFontPadding = false
            setPadding(24, 0, 24, 0)
            background = rowBackground(index == selectedIndex)
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    selectedIndex = index
                    updateRowHighlights()
                }
            }
            setOnClickListener {
                selectedIndex = index
                updateRowHighlights()
                showChannelDialog(config)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 58).apply {
                bottomMargin = 8
            }
        }

    private fun updateRowHighlights() {
        for (index in 0 until rows.childCount) {
            rows.getChildAt(index).background = rowBackground(index == selectedIndex)
        }
    }

    private fun selectedConfig(): ChannelConfigStore.ChannelConfig? =
        configs.getOrNull(selectedIndex).also {
            if (it == null) Toast.makeText(this, "No custom channel selected", Toast.LENGTH_SHORT).show()
        }

    private fun deleteSelected(config: ChannelConfigStore.ChannelConfig) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete Channel")
            .setMessage("Delete ${config.displayName}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                TmdbChannelRefresher.removeChannel(this, config)
                ChannelConfigStore.deleteCustomChannel(this, config.id)
                selectedIndex = selectedIndex.coerceAtMost((configs.size - 2).coerceAtLeast(0))
                reloadConfigs()
                Toast.makeText(this, "Channel deleted", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.setOnShowListener {
            styleDialog(dialog)
        }
        dialog.show()
    }

    private fun showChannelDialog(existing: ChannelConfigStore.ChannelConfig?) {
        val sources = ChannelConfigStore.sources
        var selectedMediaType = existing?.mediaType ?: ChannelConfigStore.MEDIA_TYPE_MOVIE
        var selectedGenreIds = existing?.genreIds.orEmpty().toSet()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 0)
        }

        val sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sources.map { it.displayName })
            setSelection(sources.indexOfFirst { it.id == existing?.sourceId }.takeIf { it >= 0 } ?: 0)
            background = fieldBackground(false)
            setPadding(18, 0, 18, 0)
            setOnFocusChangeListener { view, hasFocus -> view.background = fieldBackground(hasFocus) }
        }
        container.addView(labeled("Section", sourceSpinner))

        val mediaGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(radioButton("Movies", 1, selectedMediaType == ChannelConfigStore.MEDIA_TYPE_MOVIE))
            addView(radioButton("TV", 2, selectedMediaType == ChannelConfigStore.MEDIA_TYPE_TV))
        }
        container.addView(labeled("Media Type", mediaGroup))

        val genreBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderGenres() {
            genreBox.removeAllViews()
            ChannelConfigStore.genresForMediaType(selectedMediaType).chunked(2).forEach { rowGenres ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowGenres.forEach { genre ->
                    row.addView(
                        CheckBox(this).apply {
                            text = genre.displayName
                            textSize = 17f
                            tag = genre.id
                            isChecked = genre.id in selectedGenreIds
                            isFocusable = true
                            setTextColor(Color.WHITE)
                            setPadding(14, 0, 14, 0)
                            background = dialogControlBackground(false)
                            buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_DIALOG_ACCENT)
                            setOnFocusChangeListener { view, hasFocus ->
                                view.background = dialogControlBackground(hasFocus)
                            }
                        },
                        LinearLayout.LayoutParams(0, 56, 1f)
                    )
                }
                genreBox.addView(row)
            }
        }

        fun selectedGenresFromUi(): List<Int> =
            buildList {
                for (rowIndex in 0 until genreBox.childCount) {
                    val row = genreBox.getChildAt(rowIndex) as ViewGroup
                    for (childIndex in 0 until row.childCount) {
                        val checkbox = row.getChildAt(childIndex) as CheckBox
                        if (checkbox.isChecked) add(checkbox.tag as Int)
                    }
                }
            }

        renderGenres()
        container.addView(labeled("Genres", genreBox))

        mediaGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedGenreIds = selectedGenresFromUi().toSet()
            selectedMediaType = if (checkedId == 2) ChannelConfigStore.MEDIA_TYPE_TV else ChannelConfigStore.MEDIA_TYPE_MOVIE
            renderGenres()
        }

        val scrollView = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Create Channel" else "Channel")
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val sourceId = sources[sourceSpinner.selectedItemPosition].id
                val config = ChannelConfigStore.createCustomChannel(
                    sourceId = sourceId,
                    mediaType = selectedMediaType,
                    genreIds = selectedGenresFromUi()
                )
                if (existing != null) {
                    TmdbChannelRefresher.removeChannel(this, existing)
                    ChannelConfigStore.deleteCustomChannel(this, existing.id)
                }
                ChannelConfigStore.saveCustomChannel(this, config)
                reloadConfigs()
                refreshNow()
                Toast.makeText(this, "Channel saved", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.setOnShowListener {
            styleDialog(dialog)
        }
        dialog.show()
    }

    private fun showAboutDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 8)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        content.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.tmdb_logo)
                adjustViewBounds = true
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120)
        )

        content.addView(
            TextView(this).apply {
                text = "This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB."
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("About")
            .setView(content)
            .setPositiveButton("Close", null)
            .create()

        dialog.setOnShowListener {
            styleDialog(dialog, preferredFocusButton = AlertDialog.BUTTON_POSITIVE)
        }
        dialog.show()
    }

    private fun styleDialog(dialog: AlertDialog, preferredFocusButton: Int = AlertDialog.BUTTON_NEGATIVE) {
        dialog.window?.setBackgroundDrawable(dialogBackground())
        listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE).forEach { buttonId ->
            dialog.getButton(buttonId)?.apply {
                textSize = 18f
                setTextColor(Color.WHITE)
                isAllCaps = false
                minHeight = 64
                setPadding(24, 8, 24, 8)
                background = dialogButtonBackground(false)
                setOnFocusChangeListener { view, hasFocus -> view.background = dialogButtonBackground(hasFocus) }
            }
        }
        dialog.getButton(preferredFocusButton)?.requestFocus()
    }

    private fun labeled(label: String, child: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
            })
            addView(child)
        }

    private fun radioButton(label: String, id: Int, checked: Boolean): android.widget.RadioButton =
        android.widget.RadioButton(this).apply {
            this.id = id
            text = label
            textSize = 18f
            isChecked = checked
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_DIALOG_ACCENT)
            background = dialogControlBackground(false)
            setPadding(14, 0, 20, 0)
            setOnFocusChangeListener { view, hasFocus ->
                view.background = dialogControlBackground(hasFocus)
            }
        }

    private fun refreshNow() {
        val appContext = applicationContext
        synchronized(REFRESH_LOCK) {
            if (refreshInFlight) {
                refreshPending = true
                Log.i(TAG, "Immediate TMDB refresh already running; queued another refresh")
                return
            }
            refreshInFlight = true
        }

        Log.i(TAG, "Immediate TMDB refresh requested from editor")
        Thread {
            var refreshCount = 0
            var lastSummary: TmdbChannelRefresher.RefreshSummary? = null
            var failed = false

            do {
                synchronized(REFRESH_LOCK) {
                    refreshPending = false
                }
                refreshCount++
                lastSummary = runCatching {
                    TmdbChannelRefresher.refreshAll(appContext)
                }.onFailure { error ->
                    failed = true
                    Log.e(TAG, "Immediate TMDB refresh failed", error)
                }.getOrNull()
            } while (synchronized(REFRESH_LOCK) { refreshPending })

            synchronized(REFRESH_LOCK) {
                refreshInFlight = false
            }

            runOnUiThread {
                if (failed && lastSummary == null) {
                    TmdbRefreshScheduler.scheduleStartupRefresh(appContext)
                    Toast.makeText(this, "Refresh queued", Toast.LENGTH_SHORT).show()
                } else {
                    val suffix = if (refreshCount > 1) " ($refreshCount passes)" else ""
                    Toast.makeText(this, "Channels refreshed$suffix", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun rowBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 6f
            setColor(if (focused) COLOR_BLUE else COLOR_ROW)
            setStroke(if (focused) 3 else 1, if (focused) COLOR_DIALOG_FOCUS_STROKE else COLOR_ROW_STROKE)
        }

    private fun buttonBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 6f
            setColor(if (focused) COLOR_BLUE else COLOR_BUTTON)
            setStroke(if (focused) 3 else 1, if (focused) COLOR_DIALOG_FOCUS_STROKE else COLOR_BUTTON_STROKE)
        }

    private fun dialogControlBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 10f
            setColor(if (focused) COLOR_DIALOG_FOCUS else Color.TRANSPARENT)
            if (focused) setStroke(3, COLOR_DIALOG_FOCUS_STROKE)
        }

    private fun dialogButtonBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 8f
            setColor(if (focused) COLOR_DIALOG_FOCUS else COLOR_BUTTON)
            setStroke(if (focused) 3 else 1, if (focused) COLOR_DIALOG_FOCUS_STROKE else COLOR_BUTTON)
        }

    private fun fieldBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 8f
            setColor(if (focused) COLOR_DIALOG_FOCUS else COLOR_DIALOG_FIELD)
            setStroke(if (focused) 3 else 1, if (focused) COLOR_DIALOG_FOCUS_STROKE else COLOR_DIALOG_FIELD_STROKE)
        }

    private fun dialogBackground(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 8f
            setColor(COLOR_DIALOG_BACKGROUND)
        }

    private companion object {
        private const val TAG = "MainActivity"
        private val REFRESH_LOCK = Any()
        @Volatile
        private var refreshInFlight = false
        @Volatile
        private var refreshPending = false
        private const val COLOR_BACKGROUND = 0xFF05070A.toInt()
        private const val COLOR_ROW = 0xFF121A24.toInt()
        private const val COLOR_ROW_STROKE = 0xFF273446.toInt()
        private const val COLOR_BUTTON = 0xFF253244.toInt()
        private const val COLOR_BUTTON_STROKE = 0xFF41536A.toInt()
        private const val COLOR_BLUE = 0xFF006DFF.toInt()
        private const val COLOR_SECONDARY_TEXT = 0xFFB8C7D9.toInt()
        private const val COLOR_DIALOG_BACKGROUND = 0xFF202733.toInt()
        private const val COLOR_DIALOG_FIELD = 0xFF111A27.toInt()
        private const val COLOR_DIALOG_FIELD_STROKE = 0xFF4C617A.toInt()
        private const val COLOR_DIALOG_FOCUS = 0xFF0057D9.toInt()
        private const val COLOR_DIALOG_FOCUS_STROKE = 0xFF8FC2FF.toInt()
        private const val COLOR_DIALOG_ACCENT = 0xFF7FDBFF.toInt()
    }
}
