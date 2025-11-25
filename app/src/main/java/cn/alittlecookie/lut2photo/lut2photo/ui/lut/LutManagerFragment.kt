package cn.alittlecookie.lut2photo.lut2photo.ui.lut

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.alittlecookie.lut2photo.lut2photo.adapter.LutAdapter
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentLutManagerBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LutManagerFragment : Fragment() {
    
    private var _binding: FragmentLutManagerBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var lutAdapter: LutAdapter
    private lateinit var lutManager: LutManager
    private val lutItems = mutableListOf<LutItem>()
    
    private val importLutLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                // 处理多选文件
                val clipData = intent.clipData
                if (clipData != null) {
                    // 多个文件
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                    importMultipleLutFiles(uris)
                } else {
                    // 单个文件
                    intent.data?.let { uri ->
                        importLutFile(uri)
                    }
                }
            }
        }
    }
    
    private val exportLutLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportSelectedLuts(uri)
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLutManagerBinding.inflate(inflater, container, false)
        lutManager = LutManager(requireContext())
        
        setupViews()
        setupRecyclerView()
        loadLutFiles()
        
        return binding.root
    }
    
    private fun setupViews() {
        binding.apply {
            // 导入LUT文件
            buttonImportLut.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "text/plain", 
                        "application/octet-stream",
                        "text/cube",
                        "application/cube",
                        "application/vlt",
                        "text/vlt"
                    ))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                importLutLauncher.launch(intent)
            }
            
            // 导出LUT文件
            buttonExportLut.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                exportLutLauncher.launch(intent)
            }

            // 移除了删除选中按钮的点击事件
        }
    }
    
    private fun setupRecyclerView() {
        lutAdapter = LutAdapter(
            onItemClick = { lutItem ->
                // 点击切换选择状态
                lutAdapter.toggleSelection(lutItem)
            },
            onDeleteClick = { lutItem ->
                deleteLutFile(lutItem)
            }
        )
        
        binding.recyclerViewLuts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lutAdapter
        }
    }
    
    private fun loadLutFiles() {
        lifecycleScope.launch {
            val luts = lutManager.getAllLuts()
            lutItems.clear()
            lutItems.addAll(luts)
            lutAdapter.submitList(lutItems.toList())
            updateUI()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun importMultipleLutFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                binding.buttonImportLut.isEnabled = false
                binding.buttonImportLut.text = "导入中..."

                var successCount = 0
                var failCount = 0

                for ((index, uri) in uris.withIndex()) {
                    try {
                        val success = withContext(Dispatchers.IO) {
                            lutManager.importLut(uri)
                        }

                        if (success) {
                            successCount++
                        } else {
                            failCount++
                        }

                        // 更新进度
                        binding.buttonImportLut.text = "导入中... (${index + 1}/${uris.size})"
                    } catch (e: Exception) {
                        failCount++
                        e.printStackTrace()
                    }
                }

                // 显示结果
                val message = when {
                    failCount == 0 -> "成功导入 $successCount 个LUT文件（已自动转换为33位）"
                    successCount == 0 -> "导入失败，请检查文件格式"
                    else -> "成功导入 $successCount 个，失败 $failCount 个LUT文件"
                }

                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

                // 刷新列表
                if (successCount > 0) {
                    loadLutFiles()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "批量导入错误: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                e.printStackTrace()
            } finally {
                binding.buttonImportLut.isEnabled = true
                binding.buttonImportLut.text = "导入"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun importLutFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                binding.buttonImportLut.isEnabled = false
                binding.buttonImportLut.text = "导入中..."
                
                val success = withContext(Dispatchers.IO) {
                    lutManager.importLut(uri)
                }
                
                if (success) {
                    Toast.makeText(requireContext(), "LUT文件导入成功（已自动转换为33位）", Toast.LENGTH_SHORT).show()
                    // 强制刷新列表
                    loadLutFiles()
                } else {
                    Toast.makeText(requireContext(), "LUT文件导入失败，请检查文件格式", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导入错误: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            } finally {
                binding.buttonImportLut.isEnabled = true
                binding.buttonImportLut.text = "导入"
            }
        }
    }
    
    private fun exportSelectedLuts(targetUri: Uri) {
        lifecycleScope.launch {
            try {
                val selectedLuts = lutAdapter.getSelectedLuts()
                if (selectedLuts.isEmpty()) {
                    Toast.makeText(requireContext(), "请选择要导出的LUT文件", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val success = lutManager.exportLuts(selectedLuts, targetUri)
                if (success) {
                    Toast.makeText(requireContext(), "LUT文件导出成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "LUT文件导出失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun deleteLutFile(lutItem: LutItem) {
        lifecycleScope.launch {
            val success = lutManager.deleteLut(lutItem)
            if (success) {
                Toast.makeText(requireContext(), "LUT文件删除成功", Toast.LENGTH_SHORT).show()
                loadLutFiles()
            } else {
                Toast.makeText(requireContext(), "LUT文件删除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        binding.textLutCount.text = "共 ${lutItems.size} 个LUT文件"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}