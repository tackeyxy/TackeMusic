package com.tacke.music.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tacke.music.databinding.ActivityCacheManageBinding
import com.tacke.music.databinding.ItemCacheTypeBinding
import com.tacke.music.utils.CacheManager
import kotlinx.coroutines.launch

class CacheManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCacheManageBinding
    private lateinit var cacheAdapter: CacheTypeAdapter
    private var cacheInfoList = mutableListOf<CacheManager.CacheInfo>()

    companion object {
        const val PREFS_NAME = "cache_settings"
        const val KEY_CACHE_EXPIRY_DAYS = "cache_expiry_days"

        const val DEFAULT_CACHE_EXPIRY_DAYS = 30

        val CACHE_EXPIRY_OPTIONS = listOf(7, 15, 30, 60, 90)

        fun getCacheExpiryDays(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_CACHE_EXPIRY_DAYS, DEFAULT_CACHE_EXPIRY_DAYS)
        }

        fun setCacheExpiryDays(context: Context, days: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_CACHE_EXPIRY_DAYS, days).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCacheManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupViews()
        setupRecyclerView()
        loadCacheInfo()
    }

    override fun onResume() {
        super.onResume()
        loadCacheInfo()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener { finish() }

        binding.layoutCacheExpiry?.setOnClickListener { showCacheExpiryDialog() }
        binding.btnClearAllCache.setOnClickListener { showClearAllCacheDialog() }

        updateCacheExpiryText()
    }

    private fun setupRecyclerView() {
        cacheAdapter = CacheTypeAdapter(
            onClearClick = { cacheInfo ->
                showClearCacheTypeDialog(cacheInfo)
            }
        )
        binding.recyclerViewCacheTypes.apply {
            layoutManager = LinearLayoutManager(this@CacheManageActivity)
            adapter = cacheAdapter
        }
    }

    private fun loadCacheInfo() {
        lifecycleScope.launch {
            val cacheInfo = CacheManager.getAllCacheInfo(this@CacheManageActivity)
            cacheInfoList.clear()
            cacheInfoList.addAll(cacheInfo)
            cacheAdapter.submitList(cacheInfoList.toList())

            val totalSize = cacheInfo.sumOf { it.size }
            binding.tvTotalCacheSize.text = formatCacheSize(totalSize)
        }
    }

    private fun updateCacheExpiryText() {
        val days = getCacheExpiryDays(this)
        binding.tvCacheExpiryValue?.text = "$days 天"
    }

    private fun showCacheExpiryDialog() {
        val currentDays = getCacheExpiryDays(this)
        val options = CACHE_EXPIRY_OPTIONS.map { "$it 天" }.toTypedArray()
        val currentIndex = CACHE_EXPIRY_OPTIONS.indexOf(currentDays).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("选择缓存过期时间")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selectedDays = CACHE_EXPIRY_OPTIONS[which]
                setCacheExpiryDays(this, selectedDays)
                updateCacheExpiryText()
                Toast.makeText(this, "缓存过期时间已设置为: $selectedDays 天", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearAllCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle("清除所有缓存")
            .setMessage("确定要清除所有缓存吗？这不会影响已下载的歌曲。")
            .setPositiveButton("确定") { _, _ ->
                clearCache(null)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearCacheTypeDialog(cacheInfo: CacheManager.CacheInfo) {
        AlertDialog.Builder(this)
            .setTitle("清除${cacheInfo.description}")
            .setMessage("确定要清除${cacheInfo.description}吗？大小: ${formatCacheSize(cacheInfo.size)}")
            .setPositiveButton("确定") { _, _ ->
                clearCache(cacheInfo.type)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearCache(cacheType: String?) {
        lifecycleScope.launch {
            val success = CacheManager.clearCache(this@CacheManageActivity, cacheType)
            if (success) {
                Toast.makeText(this@CacheManageActivity, "缓存已清除", Toast.LENGTH_SHORT).show()
                loadCacheInfo()
            } else {
                Toast.makeText(this@CacheManageActivity, "清除缓存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatCacheSize(sizeBytes: Long): String {
        return when {
            sizeBytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
            sizeBytes >= 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
            sizeBytes >= 1024 -> String.format("%.2f KB", sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.statusBarPlaceholder?.layoutParams?.height = insets.top
            binding.statusBarPlaceholder?.requestLayout()
            view.updatePadding(bottom = insets.bottom)
            windowInsets
        }
    }

    inner class CacheTypeAdapter(
        private val onClearClick: (CacheManager.CacheInfo) -> Unit
    ) : RecyclerView.Adapter<CacheTypeAdapter.ViewHolder>() {

        private var items = listOf<CacheManager.CacheInfo>()

        fun submitList(newItems: List<CacheManager.CacheInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCacheTypeBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(
            private val binding: ItemCacheTypeBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(cacheInfo: CacheManager.CacheInfo) {
                binding.tvCacheTypeName.text = cacheInfo.description
                binding.tvCacheTypeSize.text = formatCacheSize(cacheInfo.size)
                binding.btnClearType.setOnClickListener {
                    onClearClick(cacheInfo)
                }
            }

            private fun formatCacheSize(sizeBytes: Long): String {
                return when {
                    sizeBytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
                    sizeBytes >= 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
                    sizeBytes >= 1024 -> String.format("%.2f KB", sizeBytes / 1024.0)
                    else -> "$sizeBytes B"
                }
            }
        }
    }
}
