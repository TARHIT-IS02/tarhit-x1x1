package com.imaginstudio.imagetools.pixellab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.imaginstudio.imagetools.pixellab.databinding.ActivityQuotesBinding
import com.imaginstudio.imagetools.pixellab.model.AssetRepository
import com.imaginstudio.imagetools.pixellab.ui.CompactDropdown
import com.imaginstudio.imagetools.pixellab.ui.ThemeManager
import kotlin.math.roundToInt

class QuotesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUOTE = "extra_quote"
    }

    private lateinit var binding: ActivityQuotesBinding
    private var currentLang = 0 // 0 = English, 1 = French
    private var categoryDropdown: CompactDropdown.Controller? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = ThemeManager.isDark(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val sysCol = ThemeManager.statusBarBg(dark)
            window.statusBarColor = sysCol
            window.navigationBarColor = sysCol
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        binding = ActivityQuotesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, sysBars.bottom)
            v.setBackgroundColor(ThemeManager.statusBarBg(dark))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.quotesRoot.setBackgroundColor(ThemeManager.workspaceBg(dark))
        binding.quotesHeaderBar.setBackgroundColor(if (dark) ThemeManager.panelBg(true) else Color.WHITE)
        binding.quotesTitle.setTextColor(ThemeManager.textPrimary(dark))
        binding.btnBackQuotes.setColorFilter(ThemeManager.textPrimary(dark))
        binding.quotesScroll.setBackgroundColor(ThemeManager.workspaceBg(dark))

        binding.btnBackQuotes.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        val langItems = listOf(
            CompactDropdown.Item("0", "English", "EN"),
            CompactDropdown.Item("1", "Français", "FR")
        )
        val langDropdown = CompactDropdown.create(this, langItems, "0") { picked ->
            currentLang = picked.id.toIntOrNull() ?: 0
            populateCategories()
        }
        binding.quoteLangSlot.removeAllViews()
        binding.quoteLangSlot.addView(langDropdown.trigger)

        populateCategories()
    }

    private fun currentMap(): Map<String, List<String>> {
        val code = if (currentLang == 0) "en" else "fr"
        return AssetRepository.loadQuotes(this, code)
    }

    private fun populateCategories() {
        val categories = currentMap().keys.toList()
        val items = categories.map { CompactDropdown.Item(it, it, it) }
        val firstCat = categories.firstOrNull() ?: ""
        val dd = categoryDropdown
        if (dd == null) {
            val ctrl = CompactDropdown.create(this, items, firstCat) { picked ->
                renderQuotes(currentMap()[picked.id].orEmpty())
            }
            categoryDropdown = ctrl
            binding.quoteCategorySlot.removeAllViews()
            binding.quoteCategorySlot.addView(ctrl.trigger)
        } else {
            dd.setItems(items, firstCat)
        }
        if (categories.isNotEmpty()) {
            renderQuotes(currentMap()[firstCat].orEmpty())
        } else {
            renderQuotes(emptyList())
        }
    }

    private fun renderQuotes(quotes: List<String>) {
        val dark = ThemeManager.isDark(this)
        binding.quotesListContainer.removeAllViews()
        for (q in quotes) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ThemeManager.dialogCardDrawable(this@QuotesActivity, dark, 10)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
                elevation = dp(1).toFloat()
            }
            val tv = TextView(this).apply {
                text = q
                textSize = 15f
                setTextColor(ThemeManager.textPrimary(dark))
            }
            card.addView(tv)
            card.setOnClickListener {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_QUOTE, q))
                finish()
            }
            binding.quotesListContainer.addView(card)
        }
    }
}
