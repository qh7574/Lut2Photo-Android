package cn.alittlecookie.lut2photo.lut2photo.ui.notifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import cn.alittlecookie.lut2photo.lut2photo.BuildConfig
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentNotificationsBinding
import cn.alittlecookie.lut2photo.lut2photo.utils.ThemeManager

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var notificationsViewModel: NotificationsViewModel
    private lateinit var themeManager: ThemeManager
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updatePermissionStatus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        notificationsViewModel = ViewModelProvider(this)[NotificationsViewModel::class.java]
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        
        themeManager = ThemeManager(requireContext())
        
        setupViews()
        updatePermissionStatus()
        setupThemeDropdown()
        
        return binding.root
    }
    
    private fun setupViews() {
        binding.apply {
            // 移除LUT管理按钮的点击事件
            // buttonLutManager.setOnClickListener {
            //     findNavController().navigate(R.id.navigation_lut_manager)
            // }
            
            // 权限检查按钮
            buttonCheckPermissions.setOnClickListener {
                checkAndRequestPermissions()
            }
            
            // 应用设置按钮
            buttonAppSettings.setOnClickListener {
                openAppSettings()
            }
            
            // 应用信息
            textAppVersion.text = getString(R.string.app_version_format, BuildConfig.VERSION_NAME)
            textAppDescription.text = getString(R.string.app_description)
        }
    }
    
    private fun setupThemeDropdown() {
        val themes = mutableListOf(
            getString(R.string.theme_light) to ThemeManager.THEME_LIGHT,
            getString(R.string.theme_dark) to ThemeManager.THEME_DARK,
            getString(R.string.theme_follow_system) to ThemeManager.THEME_SYSTEM
        )
        
        // 只在支持的设备上显示动态颜色选项
        if (themeManager.isDynamicColorSupported()) {
            themes.add(getString(R.string.theme_dynamic_color) to ThemeManager.THEME_DYNAMIC)
        }
        
        val themeNames = themes.map { it.first }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, themeNames)
        
        binding.dropdownTheme.setAdapter(adapter)

        // 设置当前主题 - 修复方法调用
        val currentTheme = themeManager.getCurrentTheme()
        val currentThemeName = themeManager.getThemeName(currentTheme)
        binding.dropdownTheme.setText(currentThemeName, false)
        
        // 设置选择监听器
        binding.dropdownTheme.setOnItemClickListener { _, _, position, _ ->
            val selectedTheme = themes[position].second
            themeManager.setTheme(selectedTheme)
            
            // 重启Activity以应用新主题
            requireActivity().recreate()
        }
    }
    
    private fun updatePermissionStatus() {
        val permissions = getRequiredPermissions()
        val grantedPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
        }
        
        binding.apply {
            textPermissionStatus.text = getString(
                R.string.permission_status_format,
                grantedPermissions.size,
                permissions.size
            )
            
            // 显示具体权限状态
            val statusText = StringBuilder()
            permissions.forEach { permission ->
                val isGranted = ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
                val permissionName = getPermissionDisplayName(permission)
                val status =
                    if (isGranted) getString(R.string.permission_granted) else getString(R.string.permission_denied)
                statusText.append("$permissionName: $status\n")
            }
            textPermissionDetails.text = statusText.toString().trim()
            
            // 更新按钮状态
            val allGranted = grantedPermissions.size == permissions.size
            buttonCheckPermissions.text =
                if (allGranted) getString(R.string.permission_check_passed) else getString(R.string.request_permissions)
            buttonCheckPermissions.isEnabled = !allGranted
        }
    }

    private fun getPermissionDisplayName(permission: String): String {
        return when (permission) {
            Manifest.permission.READ_EXTERNAL_STORAGE -> getString(R.string.permission_read_storage)
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> getString(R.string.permission_write_storage)
            Manifest.permission.READ_MEDIA_IMAGES -> getString(R.string.permission_read_images)
            Manifest.permission.POST_NOTIFICATIONS -> getString(R.string.permission_post_notifications)
            else -> permission
        }
    }
    
    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        return permissions
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = getRequiredPermissions()
        val deniedPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (deniedPermissions.isNotEmpty()) {
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
    }
    
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        setupThemeDropdown()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}