package cn.alittlecookie.lut2photo.lut2photo.camera

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.alittlecookie.lut2photo.lut2photo.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import android.widget.Toast
import android.util.Log
import java.io.File
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat


/**
 * 相机控制Fragment，实现相机控制的用户界面
 */
class CameraFragment : Fragment() {

    companion object {
        private const val TAG = "CameraFragment"

        fun newInstance(): CameraFragment {
            return CameraFragment()
        }
    }

    private val viewModel: CameraViewModel by viewModels()

    // UI组件
    private lateinit var liveViewImageView: ImageView
    private lateinit var connectionStatusText: TextView
    private lateinit var backButton: MaterialButton
    private lateinit var connectCameraButton: MaterialButton
    private lateinit var disconnectCameraButton: MaterialButton
    private lateinit var refreshCamerasButton: MaterialButton
    private lateinit var startLiveViewButton: MaterialButton
    private lateinit var stopLiveViewButton: MaterialButton
    private lateinit var captureButton: FloatingActionButton
    private lateinit var recordButton: MaterialButton
    private lateinit var autoFocusButton: MaterialButton

    // 参数控制按钮
    private lateinit var apertureButton: MaterialButton
    private lateinit var shutterSpeedButton: MaterialButton
    private lateinit var isoButton: MaterialButton
    private lateinit var whiteBalanceButton: MaterialButton
    private lateinit var focusModeButton: MaterialButton

    // 布局容器
    private lateinit var portraitLayout: LinearLayout
    private lateinit var landscapeLayout: FrameLayout
    private lateinit var controlsContainer: LinearLayout

    // 横屏布局专用组件
    private lateinit var landscapeLiveViewImageView: ImageView
    private lateinit var landscapeHintText: TextView
    private lateinit var landscapeBackButton: MaterialButton
    private lateinit var landscapeConnectionStatusText: TextView
    private lateinit var landscapeRefreshCamerasButton: MaterialButton
    private lateinit var landscapeConnectCameraButton: MaterialButton
    private lateinit var landscapeDisconnectCameraButton: MaterialButton
    private lateinit var landscapeStartLiveViewButton: MaterialButton
    private lateinit var landscapeAutoFocusButton: MaterialButton
    private lateinit var landscapeApertureButton: MaterialButton
    private lateinit var landscapeShutterSpeedButton: MaterialButton
    private lateinit var landscapeIsoButton: MaterialButton
    private lateinit var landscapeWhiteBalanceButton: MaterialButton
    private lateinit var landscapeFocusModeButton: MaterialButton
    private lateinit var landscapeRecordButton: MaterialButton
    private lateinit var landscapeCaptureButton: FloatingActionButton

    private var isRecording = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupClickListeners()
        observeViewModel()
        observeVideoStream()
        observeTetheredShooting()

        // 观察视频效果状态
        observeVideoEffects()

        // 根据屏幕方向调整布局
        adjustLayoutForOrientation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adjustLayoutForOrientation()
    }

    private fun initViews(view: View) {
        // 主要UI组件
        liveViewImageView = view.findViewById(R.id.liveViewImageView)
        connectionStatusText = view.findViewById(R.id.connectionStatusText)
        backButton = view.findViewById(R.id.backButton)
        connectCameraButton = view.findViewById(R.id.connectCameraButton)
        disconnectCameraButton = view.findViewById(R.id.disconnectCameraButton)
        refreshCamerasButton = view.findViewById(R.id.refreshCamerasButton)
        startLiveViewButton = view.findViewById(R.id.startLiveViewButton)
        stopLiveViewButton = view.findViewById(R.id.stopLiveViewButton)
        captureButton = view.findViewById(R.id.captureButton)
        recordButton = view.findViewById(R.id.recordButton)
        autoFocusButton = view.findViewById(R.id.autoFocusButton)

        // 参数控制按钮
        apertureButton = view.findViewById(R.id.apertureButton)
        shutterSpeedButton = view.findViewById(R.id.shutterSpeedButton)
        isoButton = view.findViewById(R.id.isoButton)
        whiteBalanceButton = view.findViewById(R.id.whiteBalanceButton)
        focusModeButton = view.findViewById(R.id.focusModeButton)

        // 布局容器
        portraitLayout = view.findViewById(R.id.portraitLayout)
        landscapeLayout = view.findViewById(R.id.landscapeLayout)
        controlsContainer = view.findViewById(R.id.controlsContainer)

        // 横屏布局专用组件
        landscapeLiveViewImageView = view.findViewById(R.id.landscapeLiveViewImageView)
        landscapeHintText = view.findViewById(R.id.landscapeHintText)
        landscapeBackButton = view.findViewById(R.id.landscapeBackButton)
        landscapeConnectionStatusText = view.findViewById(R.id.landscapeConnectionStatusText)
        landscapeRefreshCamerasButton = view.findViewById(R.id.landscapeRefreshCamerasButton)
        landscapeConnectCameraButton = view.findViewById(R.id.landscapeConnectCameraButton)
        landscapeDisconnectCameraButton = view.findViewById(R.id.landscapeDisconnectCameraButton)
        landscapeStartLiveViewButton = view.findViewById(R.id.landscapeStartLiveViewButton)
        landscapeAutoFocusButton = view.findViewById(R.id.landscapeAutoFocusButton)
        landscapeApertureButton = view.findViewById(R.id.landscapeApertureButton)
        landscapeShutterSpeedButton = view.findViewById(R.id.landscapeShutterSpeedButton)
        landscapeIsoButton = view.findViewById(R.id.landscapeIsoButton)
        landscapeWhiteBalanceButton = view.findViewById(R.id.landscapeWhiteBalanceButton)
        landscapeFocusModeButton = view.findViewById(R.id.landscapeFocusModeButton)
        landscapeRecordButton = view.findViewById(R.id.landscapeRecordButton)
        landscapeCaptureButton = view.findViewById(R.id.landscapeCaptureButton)

    }

    private fun setupClickListeners() {
        // 返回按钮点击事件
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        connectCameraButton.setOnClickListener {
            showCameraSelectionDialog()
        }

        disconnectCameraButton.setOnClickListener {
            viewModel.disconnectCamera()
        }

        refreshCamerasButton.setOnClickListener {
            viewModel.refreshAvailableCameras()
        }

        startLiveViewButton.setOnClickListener {
            viewModel.startLiveView()
        }

        stopLiveViewButton.setOnClickListener {
            viewModel.stopLiveView()
        }

        captureButton.setOnClickListener {
            viewModel.captureImage()
        }

        recordButton.setOnClickListener {
            if (isRecording) {
                viewModel.stopVideoRecording()
            } else {
                viewModel.startVideoRecording()
            }
        }

        autoFocusButton.setOnClickListener {
            viewModel.autoFocus()
        }

        // 参数控制按钮点击事件
        apertureButton.setOnClickListener {
            showApertureSelectionDialog()
        }

        shutterSpeedButton.setOnClickListener {
            showShutterSpeedSelectionDialog()
        }

        isoButton.setOnClickListener {
            showIsoSelectionDialog()
        }

        whiteBalanceButton.setOnClickListener {
            showWhiteBalanceSelectionDialog()
        }

        focusModeButton.setOnClickListener {
            showFocusModeSelectionDialog()
        }

        // 实时预览点击对焦
        liveViewImageView.setOnClickListener {
            viewModel.autoFocus()
        }


        // 横屏布局按钮点击事件
        setupLandscapeClickListeners()
    }

    /**
     * 设置横屏布局按钮的点击事件
     */
    private fun setupLandscapeClickListeners() {
        // 返回按钮
        landscapeBackButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // 刷新相机列表按钮
        landscapeRefreshCamerasButton.setOnClickListener {
            viewModel.refreshAvailableCameras()
        }

        // 连接和断开按钮
        landscapeConnectCameraButton.setOnClickListener {
            showCameraSelectionDialog()
        }

        landscapeDisconnectCameraButton.setOnClickListener {
            viewModel.disconnectCamera()
        }

        // 预览和对焦按钮
        landscapeStartLiveViewButton.setOnClickListener {
            viewModel.startLiveView()
        }

        landscapeAutoFocusButton.setOnClickListener {
            viewModel.autoFocus()
        }

        // 参数控制按钮
        landscapeApertureButton.setOnClickListener {
            showApertureSelectionDialog()
        }

        landscapeShutterSpeedButton.setOnClickListener {
            showShutterSpeedSelectionDialog()
        }

        landscapeIsoButton.setOnClickListener {
            showIsoSelectionDialog()
        }

        landscapeWhiteBalanceButton.setOnClickListener {
            showWhiteBalanceSelectionDialog()
        }

        landscapeFocusModeButton.setOnClickListener {
            showFocusModeSelectionDialog()
        }

        // 录制和拍照按钮
        landscapeRecordButton.setOnClickListener {
            if (isRecording) {
                viewModel.stopVideoRecording()
            } else {
                viewModel.startVideoRecording()
            }
        }

        landscapeCaptureButton.setOnClickListener {
            viewModel.captureImage()
        }

        // 横屏预览点击对焦
        landscapeLiveViewImageView.setOnClickListener {
            viewModel.autoFocus()
        }
    }

    private fun observeViewModel() {
        // 监听服务连接状态
        lifecycleScope.launch {
            viewModel.isServiceConnected.collect { connected ->
                updateConnectionUI(connected)
            }
        }

        // 监听服务状态
        lifecycleScope.launch {
            viewModel.serviceState.collect { state ->
                updateServiceStateUI(state)
            }
        }

        // 监听连接的相机
        lifecycleScope.launch {
            viewModel.connectedCamera.collect { camera ->
                updateCameraConnectionUI(camera != null)
            }
        }

        // 监听实时预览流
        lifecycleScope.launch {
            viewModel.liveViewStream.collect { bitmap ->
                updateLiveView(bitmap)
            }
        }

        // 监听录制状态
        lifecycleScope.launch {
            viewModel.isRecording.collect { recording ->
                isRecording = recording
                updateRecordingUI(recording)
            }
        }

        // 监听实时预览状态
        lifecycleScope.launch {
            viewModel.isLiveViewActive.collect { active ->
                updateLiveViewUI(active)
            }
        }

        // 监听相机参数
        lifecycleScope.launch {
            viewModel.currentParameters.collect { parameters ->
                updateParametersUI(parameters)
            }
        }

        // 监听错误消息
        lifecycleScope.launch {
            viewModel.errorMessage.collect { error ->
                if (error != null) {
                    showErrorDialog(error)
                    Toast.makeText(requireContext(), "相机错误: $error", Toast.LENGTH_SHORT).show()
                    viewModel.clearError()
                }
            }
        }
    }

    private fun adjustLayoutForOrientation() {
        val orientation = resources.configuration.orientation

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏布局：中央视频显示，两侧控制面板
            portraitLayout.visibility = View.GONE
            landscapeLayout.visibility = View.VISIBLE

            // 横屏时隐藏系统状态栏和导航栏，实现全屏显示
            enableFullScreen()
        } else {
            // 竖屏布局：上部实时监看，下部参数控制
            portraitLayout.visibility = View.VISIBLE
            landscapeLayout.visibility = View.GONE

            // 竖屏时恢复系统栏显示
            disableFullScreen()
        }
    }

    /**
     * 启用全屏模式（横屏时使用）
     */
    private fun enableFullScreen() {
        activity?.let { activity ->
            val window = activity.window
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

            // 设置状态栏和导航栏为透明
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT

            // 隐藏系统状态栏和导航栏
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

            // 设置沉浸式模式，完全隐藏系统栏
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            // 确保内容延伸到系统栏区域
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    /**
     * 禁用全屏模式（竖屏时使用）
     */
    private fun disableFullScreen() {
        activity?.let { activity ->
            val window = activity.window
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

            // 恢复系统栏设置
            WindowCompat.setDecorFitsSystemWindows(window, true)

            // 恢复状态栏颜色为透明（保持MainActivity的设置）
            window.statusBarColor =
                ContextCompat.getColor(requireContext(), android.R.color.transparent)

            // 显示系统状态栏和导航栏
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateConnectionUI(connected: Boolean) {
        val statusText = if (connected) "服务已连接" else "服务未连接"
        val textColor = if (connected) {
            resources.getColor(android.R.color.holo_green_dark, null)
        } else {
            resources.getColor(android.R.color.holo_red_dark, null)
        }

        // 更新竖屏布局状态文本
        connectionStatusText.text = statusText
        connectionStatusText.setTextColor(textColor)

        // 更新横屏布局状态文本
        landscapeConnectionStatusText.text = statusText
        landscapeConnectionStatusText.setTextColor(textColor)
    }

    private fun updateServiceStateUI(state: Int) {
        val (statusText, backgroundRes) = when (state) {
            CameraService.STATE_IDLE -> "空闲" to R.drawable.status_background_idle
            CameraService.STATE_CONNECTING -> "连接中..." to R.drawable.status_background_idle
            CameraService.STATE_CONNECTED -> "已连接" to R.drawable.status_background_connected
            CameraService.STATE_DISCONNECTING -> "断开中..." to R.drawable.status_background_idle
            CameraService.STATE_ERROR -> "错误" to R.drawable.status_background_error
            else -> "未知状态" to R.drawable.status_background_idle
        }

        val background = ContextCompat.getDrawable(requireContext(), backgroundRes)

        // 更新竖屏布局状态文本
        connectionStatusText.text = statusText
        connectionStatusText.background = background

        // 更新横屏布局状态文本
        landscapeConnectionStatusText.text = statusText
        landscapeConnectionStatusText.background = background
    }

    private fun updateCameraConnectionUI(connected: Boolean) {
        // 竖屏布局按钮
        connectCameraButton.isEnabled = !connected
        disconnectCameraButton.isEnabled = connected
        startLiveViewButton.isEnabled = connected
        captureButton.isEnabled = connected
        recordButton.isEnabled = connected
        autoFocusButton.isEnabled = connected

        // 竖屏参数控制按钮
        apertureButton.isEnabled = connected
        shutterSpeedButton.isEnabled = connected
        isoButton.isEnabled = connected
        whiteBalanceButton.isEnabled = connected
        focusModeButton.isEnabled = connected

        // 横屏布局按钮
        landscapeConnectCameraButton.isEnabled = !connected
        landscapeDisconnectCameraButton.isEnabled = connected
        landscapeStartLiveViewButton.isEnabled = connected
        landscapeCaptureButton.isEnabled = connected
        landscapeRecordButton.isEnabled = connected
        landscapeAutoFocusButton.isEnabled = connected

        // 横屏参数控制按钮
        landscapeApertureButton.isEnabled = connected
        landscapeShutterSpeedButton.isEnabled = connected
        landscapeIsoButton.isEnabled = connected
        landscapeWhiteBalanceButton.isEnabled = connected
        landscapeFocusModeButton.isEnabled = connected
    }

    private fun updateLiveView(bitmap: Bitmap?) {
        if (bitmap != null) {
            // 更新竖屏布局的预览
            liveViewImageView.setImageBitmap(bitmap)
            liveViewImageView.visibility = View.VISIBLE

            // 更新横屏布局的预览
            landscapeLiveViewImageView.setImageBitmap(bitmap)
            landscapeLiveViewImageView.visibility = View.VISIBLE
            landscapeHintText.visibility = View.GONE

            Log.d(TAG, "Live view frame updated: ${bitmap.width}x${bitmap.height}")
        } else {
            // 隐藏竖屏预览
            liveViewImageView.visibility = View.GONE

            // 隐藏横屏预览，显示提示文本
            landscapeLiveViewImageView.visibility = View.GONE
            landscapeHintText.visibility = View.VISIBLE
        }
    }

    /**
     * 观察视频流状态
     */
    private fun observeVideoStream() {
        lifecycleScope.launch {
            // 观察视频流处理状态
            viewModel.isVideoStreamProcessing.collect { isProcessing ->
                updateVideoStreamStatus(isProcessing)
            }
        }

        lifecycleScope.launch {
            // 观察当前视频帧
            viewModel.currentVideoFrame.collect { frame ->
                frame?.let {
                    updateVideoFrameDisplay(it)
                }
            }
        }

        lifecycleScope.launch {
            // 观察视频帧率
            viewModel.videoFrameRate.collect { frameRate ->
                updateFrameRateDisplay(frameRate)
            }
        }

        lifecycleScope.launch {
            // 观察视频流错误
            viewModel.videoStreamError.collect { error ->
                error?.let {
                    Log.e(TAG, "Video stream error: $it")
                    Toast.makeText(requireContext(), "视频流错误: $it", Toast.LENGTH_SHORT).show()
                    // 自动清理错误
                    viewModel.clearVideoStreamError()
                }
            }
        }
    }

    /**
     * 观察联机拍摄状态
     */
    private fun observeTetheredShooting() {
        lifecycleScope.launch {
            // 观察联机拍摄激活状态
            viewModel.isTetheredShootingActive.collect { isActive ->
                updateTetheredShootingStatus(isActive)
            }
        }

        lifecycleScope.launch {
            // 观察拍摄进度
            viewModel.tetheredShootingProgress.collect { progress ->
                updateTetheredShootingProgress(progress)
            }
        }

        lifecycleScope.launch {
            // 观察最新拍摄的照片
            viewModel.lastCapturedPhoto.collect { photo ->
                photo?.let {
                    updateLastCapturedPhoto(it)
                }
            }
        }

        lifecycleScope.launch {
            // 观察联机拍摄错误
            viewModel.tetheredShootingError.collect { error ->
                error?.let {
                    Log.e(TAG, "Tethered shooting error: $it")
                    Toast.makeText(requireContext(), "联机拍摄错误: $it", Toast.LENGTH_SHORT).show()
                    // 自动清理错误
                    viewModel.clearTetheredShootingError()
                }
            }
        }
    }

    /**
     * 更新视频流状态显示
     */
    private fun updateVideoStreamStatus(isProcessing: Boolean) {
        Log.d(TAG, "Video stream processing: $isProcessing")
        // TODO: 更新UI显示视频流状态
    }

    /**
     * 更新视频帧显示
     */
    private fun updateVideoFrameDisplay(frame: Bitmap) {
        // TODO: 在专用的视频显示View中显示处理后的帧
        Log.d(TAG, "Video frame updated: ${frame.width}x${frame.height}")
    }

    /**
     * 更新帧率显示
     */
    private fun updateFrameRateDisplay(frameRate: Float) {
        Log.d(TAG, "Video frame rate: ${String.format("%.1f", frameRate)} fps")
        // TODO: 更新UI显示帧率
    }

    /**
     * 更新联机拍摄状态显示
     */
    private fun updateTetheredShootingStatus(isActive: Boolean) {
        Log.d(TAG, "Tethered shooting active: $isActive")
        // TODO: 更新UI显示联机拍摄状态
        // binding.tetheredShootingStatusText.text = if (isActive) "联机拍摄已激活" else "联机拍摄未激活"
        // binding.tetheredShootingButton.isEnabled = isActive
    }

    /**
     * 更新联机拍摄进度显示
     */
    private fun updateTetheredShootingProgress(progress: TetheredShootingManager.ShootingProgress?) {
        progress?.let {
            Log.d(
                TAG,
                "Tethered shooting progress: ${it.stage} - ${it.progress * 100}% - ${it.message}"
            )
            // TODO: 更新UI显示拍摄进度
            // binding.progressBar.progress = (it.progress * 100).toInt()
            // binding.progressText.text = it.message
            // binding.progressBar.visibility = if (it.stage == TetheredShootingManager.ShootingStage.COMPLETED) View.GONE else View.VISIBLE
        } ?: run {
            // 隐藏进度显示
            // binding.progressBar.visibility = View.GONE
            // binding.progressText.text = ""
        }
    }

    /**
     * 更新最新拍摄照片显示
     */
    private fun updateLastCapturedPhoto(photo: TetheredShootingManager.CapturedPhoto) {
        Log.d(TAG, "Last captured photo: ${photo.originalFile.name}")
        // TODO: 更新UI显示最新拍摄的照片
        // photo.thumbnail?.let { thumbnail ->
        //     binding.lastPhotoThumbnail.setImageBitmap(thumbnail)
        //     binding.lastPhotoThumbnail.visibility = View.VISIBLE
        // }
        // binding.lastPhotoInfo.text = "拍摄时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(photo.captureTime)}"

        // 显示成功提示
        Toast.makeText(
            requireContext(),
            "照片拍摄完成: ${photo.originalFile.name}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 观察视频效果状态
     */
    private fun observeVideoEffects() {
        // 观察LUT效果状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.lutEnabled.collect { enabled ->
                updateLutEffectStatus(enabled)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.lutIntensity.collect { intensity ->
                updateLutIntensity(intensity)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentLutFile.collect { lutFile ->
                updateCurrentLutFile(lutFile)
            }
        }

        // 观察峰值显示状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.peakingEnabled.collect { enabled ->
                updatePeakingEffectStatus(enabled)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.peakingThreshold.collect { threshold ->
                updatePeakingThreshold(threshold)
            }
        }

        // 观察波形图状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.waveformEnabled.collect { enabled ->
                updateWaveformStatus(enabled)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.waveformHeight.collect { height ->
                updateWaveformHeight(height)
            }
        }

        // 观察可用LUT文件列表
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.availableLuts.collect { luts ->
                updateAvailableLuts(luts)
            }
        }

        // 观察视频效果错误
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.videoEffectsError.collect { error ->
                error?.let {
                    Toast.makeText(requireContext(), "视频效果错误: $it", Toast.LENGTH_SHORT).show()
                    viewModel.clearVideoEffectsError()
                }
            }
        }
    }

    /**
     * 更新LUT效果状态
     */
    private fun updateLutEffectStatus(enabled: Boolean) {
        // TODO: 更新LUT效果开关UI状态
        Log.d(TAG, "LUT effect status: $enabled")
    }

    /**
     * 更新LUT强度
     */
    private fun updateLutIntensity(intensity: Float) {
        // TODO: 更新LUT强度滑块UI
        Log.d(TAG, "LUT intensity: $intensity")
    }

    /**
     * 更新当前LUT文件
     */
    private fun updateCurrentLutFile(lutFile: File?) {
        // TODO: 更新当前LUT文件显示
        Log.d(TAG, "Current LUT file: ${lutFile?.name ?: "None"}")
    }

    /**
     * 更新峰值显示状态
     */
    private fun updatePeakingEffectStatus(enabled: Boolean) {
        // TODO: 更新峰值显示开关UI状态
        Log.d(TAG, "Peaking effect status: $enabled")
    }

    /**
     * 更新峰值显示阈值
     */
    private fun updatePeakingThreshold(threshold: Float) {
        // TODO: 更新峰值显示阈值滑块UI
        Log.d(TAG, "Peaking threshold: $threshold")
    }

    /**
     * 更新波形图状态
     */
    private fun updateWaveformStatus(enabled: Boolean) {
        // TODO: 更新波形图开关UI状态
        Log.d(TAG, "Waveform status: $enabled")
    }

    /**
     * 更新波形图高度
     */
    private fun updateWaveformHeight(height: Float) {
        // TODO: 更新波形图高度滑块UI
        Log.d(TAG, "Waveform height: $height")
    }

    /**
     * 更新可用LUT文件列表
     */
    private fun updateAvailableLuts(luts: List<File>) {
        // TODO: 更新LUT文件选择器UI
        Log.d(TAG, "Available LUTs: ${luts.size}")
    }

    private fun updateLiveViewUI(active: Boolean) {
        startLiveViewButton.isEnabled = !active && viewModel.isCameraConnected()
        stopLiveViewButton.isEnabled = active
    }

    private fun updateRecordingUI(recording: Boolean) {
        if (recording) {
            recordButton.text = "停止录制"
            recordButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
        } else {
            recordButton.text = "开始录制"
            recordButton.setBackgroundColor(
                resources.getColor(
                    android.R.color.holo_blue_dark,
                    null
                )
            )
        }
    }

    private fun updateParametersUI(parameters: ICameraDevice.CameraParameters?) {
        parameters?.let {
            apertureButton.text = "光圈: ${it.aperture ?: "自动"}"
            shutterSpeedButton.text = "快门: ${it.shutterSpeed ?: "自动"}"
            isoButton.text = "ISO: ${it.iso ?: "自动"}"
            whiteBalanceButton.text = "白平衡: ${it.whiteBalance ?: "自动"}"
            focusModeButton.text = "对焦: ${it.focusMode ?: "自动"}"
        }
    }

    private fun showCameraSelectionDialog() {
        val cameras = viewModel.availableCameras.value
        if (cameras.isEmpty()) {
            showErrorDialog("未发现可用相机，请检查USB连接")
            return
        }

        val cameraNames = cameras.map { "${it.deviceName} (${it.manufacturerName})" }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择相机")
            .setItems(cameraNames) { _, which ->
                viewModel.connectToCamera(cameras[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showApertureSelectionDialog() {
        val capabilities = viewModel.cameraCapabilities.value
        val apertures = capabilities?.supportedApertures

        if (apertures.isNullOrEmpty()) {
            showErrorDialog("相机不支持光圈调节")
            return
        }

        showParameterSelectionDialog(
            title = "选择光圈值",
            items = apertures.map { "f/$it" }.toTypedArray(),
            onItemSelected = { index ->
                viewModel.setAperture(apertures[index])
            }
        )
    }

    private fun showShutterSpeedSelectionDialog() {
        val capabilities = viewModel.cameraCapabilities.value
        val shutterSpeeds = capabilities?.supportedShutterSpeeds

        if (shutterSpeeds.isNullOrEmpty()) {
            showErrorDialog("相机不支持快门速度调节")
            return
        }

        showParameterSelectionDialog(
            title = "选择快门速度",
            items = shutterSpeeds.toTypedArray(),
            onItemSelected = { index ->
                viewModel.setShutterSpeed(shutterSpeeds[index])
            }
        )
    }

    private fun showIsoSelectionDialog() {
        val capabilities = viewModel.cameraCapabilities.value
        val isoValues = capabilities?.supportedIsoValues

        if (isoValues.isNullOrEmpty()) {
            showErrorDialog("相机不支持ISO调节")
            return
        }

        showParameterSelectionDialog(
            title = "选择ISO值",
            items = isoValues.map { "ISO $it" }.toTypedArray(),
            onItemSelected = { index ->
                viewModel.setIso(isoValues[index])
            }
        )
    }

    private fun showWhiteBalanceSelectionDialog() {
        val capabilities = viewModel.cameraCapabilities.value
        val whiteBalances = capabilities?.supportedWhiteBalances

        if (whiteBalances.isNullOrEmpty()) {
            showErrorDialog("相机不支持白平衡调节")
            return
        }

        showParameterSelectionDialog(
            title = "选择白平衡",
            items = whiteBalances.toTypedArray(),
            onItemSelected = { index ->
                viewModel.setWhiteBalance(whiteBalances[index])
            }
        )
    }

    private fun showFocusModeSelectionDialog() {
        val capabilities = viewModel.cameraCapabilities.value
        val focusModes = capabilities?.supportedFocusModes

        if (focusModes.isNullOrEmpty()) {
            showErrorDialog("相机不支持对焦模式调节")
            return
        }

        showParameterSelectionDialog(
            title = "选择对焦模式",
            items = focusModes.toTypedArray(),
            onItemSelected = { index ->
                viewModel.setFocusMode(focusModes[index])
            }
        )
    }

    private fun showParameterSelectionDialog(
        title: String,
        items: Array<String>,
        onItemSelected: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(items) { _, which ->
                onItemSelected(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showErrorDialog(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

}