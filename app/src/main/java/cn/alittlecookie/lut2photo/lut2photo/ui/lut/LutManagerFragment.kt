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
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.alittlecookie.lut2photo.lut2photo.adapter.LutAdapter
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentLutManagerBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.VltFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LutManagerFragment : Fragment() {
    
    private var _binding: FragmentLutManagerBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var lutAdapter: LutAdapter
    private lateinit var lutManager: LutManager
    private lateinit var vltFileManager: VltFileManager
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
    
    private val exportVltLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportSelectedVlts(uri)
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
        vltFileManager = VltFileManager(requireContext())
        
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
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                importLutLauncher.launch(intent)
            }
            
            // 导出 CUBE 文件
            buttonExportLut.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                exportLutLauncher.launch(intent)
            }

            // 导出 VLT 文件
            buttonExportVlt.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                exportVltLauncher.launch(intent)
            }
        }
    }

    private fun setupRecyclerView() {
        lutAdapter = LutAdapter(
            onItemClick = { lutItem ->
                // 点击切换选择状态
                lutAdapter.toggleSelection(lutItem)
                // 更新导出按钮状态
                updateExportButtonsState()
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
            android.util.Log.d("LutManagerFragment", "加载了 ${luts.size} 个 LUT 文件")
            
            // 打印每个 LUT 的 VLT 信息
            luts.forEach { lut ->
                android.util.Log.d("LutManagerFragment", "LUT: ${lut.name}, vltFileName=${lut.vltFileName}, uploadName=${lut.uploadName}")
            }
            
            lutItems.clear()
            lutItems.addAll(luts)
            lutAdapter.submitList(lutItems.toList())
            updateUI()
            updateExportButtonsState()
        }
    }

    private fun isUriSupported(uri: Uri): Boolean {
        val documentFile = DocumentFile.fromSingleUri(requireContext(), uri)
        val fileName = documentFile?.name ?: return false
        val lowerName = fileName.lowercase()
        return lowerName.endsWith(".cube") || lowerName.endsWith(".vlt")
    }

    @SuppressLint("SetTextI18n")
    private fun importMultipleLutFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                // 1. 过滤文件
                val supportedUris = uris.filter { isUriSupported(it) }
                val ignoredCount = uris.size - supportedUris.size

                if (supportedUris.isEmpty()) {
                    Toast.makeText(requireContext(), "未找到支持的文件，仅支持 .cube 和 .vlt", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (ignoredCount > 0) {
                    Toast.makeText(requireContext(), "已忽略 $ignoredCount 个不支持的文件", Toast.LENGTH_SHORT).show()
                }

                binding.buttonImportLut.isEnabled = false
                binding.buttonImportLut.text = "导入中..."

                var successCount = 0
                var failCount = 0

                for ((index, uri) in supportedUris.withIndex()) {
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
                        binding.buttonImportLut.text = "导入中... (${index + 1}/${supportedUris.size})"
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
        if (!isUriSupported(uri)) {
            Toast.makeText(requireContext(), "不支持的文件格式，仅支持 .cube 和 .vlt", Toast.LENGTH_SHORT).show()
            return
        }

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
                    Toast.makeText(requireContext(), "CUBE文件导出成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "CUBE文件导出失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun exportSelectedVlts(targetUri: Uri) {
        lifecycleScope.launch {
            try {
                val selectedLuts = lutAdapter.getSelectedLuts()
                if (selectedLuts.isEmpty()) {
                    Toast.makeText(requireContext(), "请选择要导出的 LUT", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // 过滤出有 VLT 文件的 LUT
                val exportableLuts = selectedLuts.filter { it.vltFileName != null && it.uploadName != null }
                if (exportableLuts.isEmpty()) {
                    Toast.makeText(requireContext(), "选中的 LUT 没有 VLT 格式文件", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val targetDir = DocumentFile.fromTreeUri(requireContext(), targetUri) ?: return@launch
                
                var successCount = 0
                var failCount = 0
                
                exportableLuts.forEach { lutItem ->
                    try {
                        // 获取 VLT 文件
                        val vltFile = vltFileManager.getVltFile(lutItem.vltFileName!!)
                        if (vltFile != null && vltFile.exists()) {
                            // 生成导出文件名（8位）
                            val exportFileName = "${lutItem.uploadName}.vlt"
                            
                            // 创建目标文件
                            val targetFile = targetDir.createFile("application/octet-stream", exportFileName)
                            targetFile?.let { docFile ->
                                requireContext().contentResolver.openOutputStream(docFile.uri)?.use { output ->
                                    vltFile.inputStream().use { input ->
                                        input.copyTo(output)
                                    }
                                }
                                successCount++
                            } ?: run {
                                failCount++
                            }
                        } else {
                            failCount++
                        }
                    } catch (e: Exception) {
                        failCount++
                        android.util.Log.e("LutManagerFragment", "导出 VLT 失败", e)
                    }
                }
                
                // 显示结果
                val message = when {
                    failCount == 0 -> "成功导出 $successCount 个 VLT 文件"
                    successCount == 0 -> "导出失败"
                    else -> "成功导出 $successCount 个，失败 $failCount 个"
                }
                
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出错误: ${e.message}", Toast.LENGTH_SHORT).show()
                android.util.Log.e("LutManagerFragment", "导出错误", e)
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

    /**
     * 更新导出按钮状态
     */
    private fun updateExportButtonsState() {
        val selectedLuts = lutAdapter.getSelectedLuts()
        val hasVltLuts = selectedLuts.any { it.vltFileName != null }
        
        // 导出 CUBE 按钮：选中任意 LUT 时启用
        binding.buttonExportLut.isEnabled = selectedLuts.isNotEmpty()
        
        // 导出 VLT 按钮：选中有 VLT 的 LUT 时启用
        binding.buttonExportVlt.isEnabled = hasVltLuts && selectedLuts.isNotEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
