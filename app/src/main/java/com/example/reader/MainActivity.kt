package com.example.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.example.reader.navigation.NavGraph
import com.example.reader.ui.theme.ThemeState
import com.example.reader.util.PermissionHelper
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // navController 必须在读取 ThemeState.themeMode 之前创建，
            // 否则主题切换会触发整个 block 重组，rememberNavController() 生成新实例，
            // 导致导航栈被清空（白屏/闪退）
            val navController = rememberNavController()
            val permissionsState = rememberMultiplePermissionsState(
                permissions = PermissionHelper.requiredPermissions
            )

            val colorScheme = when (ThemeState.themeMode) {
                ThemeState.ThemeMode.LIGHT -> lightColorScheme()
                ThemeState.ThemeMode.DARK -> darkColorScheme()
                ThemeState.ThemeMode.AMOLED_BLACK -> darkColorScheme().copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121212)
                )
            }
            MaterialTheme(colorScheme = colorScheme) {
                if (permissionsState.allPermissionsGranted) {
                    NavGraph(navController = navController)
                } else {
                    PermissionScreen(
                        onRequestPermission = { permissionsState.launchMultiplePermissionRequest() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "需要读取存储权限",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "为了扫描和展示您手机中的图片和视频，需要您授权读取权限。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("授予权限")
            }
        }
    }
}
