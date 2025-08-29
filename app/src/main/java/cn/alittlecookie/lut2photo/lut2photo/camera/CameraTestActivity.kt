package cn.alittlecookie.lut2photo.lut2photo.camera

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
// import cn.alittlecookie.lut2photo.ui.theme.Lut2PhotoTheme
import kotlinx.coroutines.launch
import cn.alittlecookie.lut2photo.lut2photo.camera.UsbCameraManager
import cn.alittlecookie.lut2photo.lut2photo.camera.ICameraDevice

/**
 * 隐藏的相机测试Activity
 * 用于调试相机连接和功能
 */
class CameraTestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraTestActivity"

        fun createIntent(context: Context): Intent {
            return Intent(context, CameraTestActivity::class.java)
        }
    }

    private var cameraService: CameraService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.CameraServiceBinder
            cameraService = binder.getService()
            serviceBound = true
            Log.d(TAG, "相机服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            serviceBound = false
            Log.d(TAG, "相机服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "CameraTestActivity 启动")

        // 绑定相机服务
        val intent = Intent(this, CameraService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme {
                CameraTestScreen(
                    cameraService = cameraService,
                    onRefreshCameras = {
                        cameraService?.refreshAvailableCameras()
                    },
                    onConnectCamera = { cameraInfo ->
                        cameraService?.connectToCamera(cameraInfo)
                    },
                    onDisconnectCamera = {
                        cameraService?.disconnectCamera()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraTestScreen(
    cameraService: CameraService?,
    onRefreshCameras: () -> Unit,
    onConnectCamera: (UsbCameraManager.UsbCameraInfo) -> Unit,
    onDisconnectCamera: () -> Unit
) {
    val context = LocalContext.current

    // 收集服务状态
    val serviceState by (cameraService?.serviceState?.collectAsState() ?: remember {
        mutableStateOf(
            CameraService.STATE_IDLE
        )
    })
    val connectedCamera by (cameraService?.connectedCamera?.collectAsState()
        ?: remember { mutableStateOf(null) })
    val availableCameras by (cameraService?.availableCameras?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) })
    val errorMessage by (cameraService?.errorMessage?.collectAsState() ?: remember {
        mutableStateOf(
            null
        )
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("相机调试工具") },
                actions = {
                    TextButton(
                        onClick = onRefreshCameras
                    ) {
                        Text("刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 服务状态卡片
            item {
                ServiceStatusCard(
                    serviceState = serviceState,
                    connectedCamera = connectedCamera,
                    errorMessage = errorMessage
                )
            }

            // 可用相机列表
            item {
                Text(
                    text = "可用相机 (${availableCameras.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (availableCameras.isEmpty()) {
                item {
                    Card {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "未发现相机设备",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            } else {
                items(availableCameras) { cameraInfo ->
                    CameraInfoCard(
                        cameraInfo = cameraInfo,
                        isConnected = connectedCamera != null,
                        onConnect = { onConnectCamera(cameraInfo) },
                        onDisconnect = onDisconnectCamera
                    )
                }
            }
        }
    }
}

@Composable
fun ServiceStatusCard(
    serviceState: Int,
    connectedCamera: ICameraDevice?,
    errorMessage: String?
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "服务状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("状态:")
                Text(
                    text = when (serviceState) {
                        CameraService.STATE_IDLE -> "空闲"
                        CameraService.STATE_CONNECTING -> "连接中"
                        CameraService.STATE_CONNECTED -> "已连接"
                        CameraService.STATE_DISCONNECTING -> "断开中"
                        CameraService.STATE_ERROR -> "错误"
                        else -> "未知"
                    },
                    color = when (serviceState) {
                        CameraService.STATE_CONNECTED -> MaterialTheme.colorScheme.primary
                        CameraService.STATE_ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("连接设备:")
                Text(
                    text = if (connectedCamera != null) "是" else "否",
                    color = if (connectedCamera != null)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            if (errorMessage != null) {
                Text(
                    text = "错误: $errorMessage",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun CameraInfoCard(
    cameraInfo: UsbCameraManager.UsbCameraInfo,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = cameraInfo.productName ?: cameraInfo.deviceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            InfoRow("设备名称", cameraInfo.deviceName)
            InfoRow("厂商ID", "0x${cameraInfo.vendorId.toString(16).uppercase()}")
            InfoRow("产品ID", "0x${cameraInfo.productId.toString(16).uppercase()}")
            InfoRow("制造商", cameraInfo.manufacturerName ?: "未知")
            InfoRow("序列号", cameraInfo.serialNumber ?: "未知")
            InfoRow("权限状态", if (cameraInfo.hasPermission) "已授权" else "未授权")

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("断开连接")
                    }
                } else {
                    Button(
                        onClick = {
                            if (cameraInfo.hasPermission) {
                                onConnect()
                            } else {
                                Toast.makeText(context, "需要先授权USB权限", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("连接")
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}