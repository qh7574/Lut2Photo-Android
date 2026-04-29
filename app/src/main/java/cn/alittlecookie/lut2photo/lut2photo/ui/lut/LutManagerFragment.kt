package cn.alittlecookie.lut2photo.lut2photo.ui.lut

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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

class LutManagerFragment : Fragment(), LutAdapter.SelectionCallback {

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
                val clipData = intent.clipData
                if (clipData != null) {
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                    importMultipleLutFiles(uris)
                } else {
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

    // 返回键处理
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (lutAdapter.isSelectionMode) {
                lutAdapter.exitSelectionMode()
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
        setupSelectionToolbar()
        loadLutFiles()

        // 注册返回键回调
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback
        )

        return binding.root
    }

    private fun setupViews() {
        binding.apply {
            buttonImportLut.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                importLutLauncher.launch(intent)
            }
        }
    }

    private fun setupSelectionToolbar() {
        with(binding) {
            buttonExitSelection.setOnClickListener {
                lutAdapter.exitSelectionMode()
            }

            buttonSelectAll.setOnClickListener {
                lutAdapter.selectAll()
            }

            buttonDeselectAll.setOnClickListener {
                lutAdapter.deselectAll()
            }

            buttonExport.setOnClickListener {
                showExportDialog()
            }

            buttonDelete.setOnClickListener {
                showDeleteConfirmationDialog()
            }
        }
    }

    private fun setupRecyclerView() {
        lutAdapter = LutAdapter(this)

        binding.recyclerViewLuts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = lutAdapter
        }
    }

    private fun loadLutFiles() {
        lifecycleScope.launch {
            val luts = lutManager.getAllLuts()
            android.util.Log.d("LutManagerFragment", "加载了 ${luts.size} 个 LUT 文件")

            luts.forEach { lut ->
                android.util.Log.d("LutManagerFragment", "LUT: ${lut.name}, vltFileName=${lut.vltFileName}, uploadName=${lut.uploadName}")
            }

            lutItems.clear()
            lutItems.addAll(luts)
            lutAdapter.submitList(lutItems.toList())
            updateUI()
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

                        binding.buttonImportLut.text = "导入中... (${index + 1}/${supportedUris.size})"
                    } catch (e: Exception) {
                        failCount++
                        e.printStackTrace()
                    }
                }

                val message = when {
                    failCount == 0 -> "成功导入 $successCount 个LUT文件（已自动转换为33位）"
                    successCount == 0 -> "导入失败，请检查文件格式"
                    else -> "成功导入 $successCount 个，失败 $failCount 个LUT文件"
                }

                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

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

    private fun showExportDialog() {
        val selectedLuts = lutAdapter.getSelectedLuts()
        if (selectedLuts.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择要导出的LUT文件", Toast.LENGTH_SHORT).show()
            return
        }

        val hasVltLuts = selectedLuts.any { it.vltFileName != null && it.uploadName != null }

        val options = mutableListOf("导出 CUBE 文件")
        if (hasVltLuts) {
            options.add("导出 VLT 文件")
        }

        AlertDialog.Builder(requireActivity())
            .setTitle("选择导出格式")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        exportLutLauncher.launch(intent)
                    }

                    1 -> {
                        if (hasVltLuts) {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            exportVltLauncher.launch(intent)
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirmationDialog() {
        val selectedLuts = lutAdapter.getSelectedLuts()
        if (selectedLuts.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择要删除的LUT文件", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireActivity())
            .setTitle("确认删除")
            .setMessage("确定要删除选中的 ${selectedLuts.size} 个LUT文件吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedLuts(selectedLuts)
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun deleteSelectedLuts(selectedLuts: List<LutItem>) {
        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            for (lutItem in selectedLuts) {
                try {
                    val success = withContext(Dispatchers.IO) {
                        lutManager.deleteLut(lutItem)
                    }
                    if (success) {
                        successCount++
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    failCount++
                    android.util.Log.e("LutManagerFragment", "删除 LUT 失败", e)
                }
            }

            val message = when {
                failCount == 0 -> "成功删除 $successCount 个LUT文件"
                successCount == 0 -> "删除失败"
                else -> "成功删除 $successCount 个，失败 $failCount 个"
            }

            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

            lutAdapter.exitSelectionMode()
            loadLutFiles()
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
                        val vltFile = vltFileManager.getVltFile(lutItem.vltFileName!!)
                        if (vltFile != null && vltFile.exists()) {
                            val exportFileName = "${lutItem.uploadName}.vlt"

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

    // SelectionCallback 实现
    override fun onSelectionModeChanged(enabled: Boolean) {
        binding.selectionToolbar.visibility = if (enabled) View.VISIBLE else View.GONE
        backPressedCallback.isEnabled = enabled

        binding.textTitle.text = if (enabled) "选择LUT" else "LUT管理"
    }

    override fun onSelectionCountChanged(count: Int) {
        binding.buttonExport.text = if (count > 0) "导出($count)" else "导出"
        binding.buttonExport.isEnabled = count > 0
        binding.buttonDelete.text = if (count > 0) "删除($count)" else "删除"
        binding.buttonDelete.isEnabled = count > 0
    }

    override fun onItemClick(lutItem: LutItem) {
        // 普通点击不做任何操作
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