package com.beacon.testapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beacon.testapp.ui.theme.BeaconTestTheme
import com.beacon.testapp.ui.theme.CardIdle
import com.beacon.testapp.ui.theme.Ink
import com.beacon.testapp.ui.theme.MutedGray
import com.beacon.testapp.ui.theme.PageBackground
import com.beacon.testapp.ui.theme.RadioIdle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: BeaconScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.WHITE,
                AndroidColor.WHITE,
            ),
        )
        setContent {
            BeaconTestTheme {
                BeaconScanScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppForeground(true)
    }

    override fun onStop() {
        viewModel.setAppForeground(false)
        super.onStop()
    }
}

@Composable
private fun BeaconScanScreen(viewModel: BeaconScanViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var waitingForBackgroundLocation by remember { mutableStateOf(false) }
    var askedBatteryExemption by remember { mutableStateOf(false) }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    fun requestBatteryExemptionIfNeeded() {
        if (askedBatteryExemption) return
        if (BatteryOptimizations.isIgnoring(context)) return
        askedBatteryExemption = true
        batteryLauncher.launch(BatteryOptimizations.requestIntent(context))
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        startScanIfReady(context.getSystemService(BluetoothManager::class.java), viewModel) { message ->
            permissionMessage = message
        }
        requestBatteryExemptionIfNeeded()
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (!allGranted) {
            permissionMessage = "비콘 스캔에는 블루투스와 위치 권한이 필요합니다."
            return@rememberLauncherForActivityResult
        }
        if (ScanPermissions.needsBackgroundLocation() && !ScanPermissions.hasBackgroundLocation(context)) {
            waitingForBackgroundLocation = true
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return@rememberLauncherForActivityResult
        }
        startScanIfReady(context.getSystemService(BluetoothManager::class.java), viewModel) { message ->
            permissionMessage = message
        }
        requestBatteryExemptionIfNeeded()
    }

    LaunchedEffect(Unit) {
        val missing = ScanPermissions.missingForeground(context)
        when {
            missing.isNotEmpty() -> foregroundLauncher.launch(missing.toTypedArray())
            ScanPermissions.needsBackgroundLocation() && !ScanPermissions.hasBackgroundLocation(context) -> {
                waitingForBackgroundLocation = true
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            else -> {
                startScanIfReady(
                    context.getSystemService(BluetoothManager::class.java),
                    viewModel,
                ) { message ->
                    permissionMessage = message
                }
                requestBatteryExemptionIfNeeded()
            }
        }
    }

    val closest = state.closestBeacon
    val liveClosest = state.beacons.filter { it.isInMeasureRange() }.closestOrNull()
    val floorBeacon = liveClosest ?: closest
    val notice = permissionMessage ?: state.errorMessage
    val selectedFloor = state.selectedFloor
    val heroText = BeaconDisplayFormat.floorLabelFromCode(selectedFloor) ?: "찾는 중"
    val heroColor = when (selectedFloor) {
        "B1" -> Color(BeaconDisplayFormat.COLOR_B1)
        "B2" -> Color(BeaconDisplayFormat.COLOR_B2)
        "1F" -> Color(BeaconDisplayFormat.COLOR_1F)
        else -> Ink
    }
    val headline = when {
        notice != null -> "확인이 필요해요"
        waitingForBackgroundLocation && closest == null && liveClosest == null -> "권한을 허용해 주세요"
        selectedFloor != null -> "지금 주차한 층"
        floorBeacon == null -> if (state.isMeasuring) "주차 위치를 찾고 있어요" else "비콘을 기다리는 중"
        else -> "지금 주차한 층"
    }
    val subtitle = when {
        notice != null -> notice
        waitingForBackgroundLocation && closest == null && liveClosest == null ->
            "항상 허용을 선택하면 화면을 닫아도 측정합니다"
        else -> null
    }
    val infoTitle = floorBeacon?.name?.ifBlank { "비콘" } ?: "수신 대기"
    val infoDetail = when {
        notice != null -> notice
        liveClosest != null -> "5m 안에서 측정 중"
        floorBeacon != null -> "마지막에 측정한 비콘입니다"
        state.isMeasuring -> "5m 이내 Major 100 비콘을 측정하는 중"
        else -> "비콘이 잡히면 깨어나서 측정합니다"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PageBackground,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "차어디",
                color = Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(36.dp))
            Text(
                text = headline,
                color = Ink,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = subtitle,
                    color = MutedGray,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = heroText,
                color = heroColor,
                fontSize = 56.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                modifier = Modifier.fillMaxWidth(),
            )
            if (floorBeacon != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = floorBeacon.name.ifBlank { "비콘" },
                    color = Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            StatusCard(
                title = infoTitle,
                detail = infoDetail,
                pingSeq = state.pingSeq,
                receiving = state.isInMeasureRange,
            )
            Spacer(modifier = Modifier.height(20.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = "주차이력",
                        color = Ink,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (state.approachHistory.isEmpty()) {
                    item {
                        Text(
                            text = "5m 안으로 들어가면 비콘별 최종 측정이 남습니다",
                            color = MutedGray,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    items(
                        items = state.approachHistory,
                        key = { item -> item.beaconId.ifBlank { item.visitId.ifBlank { "${item.name}-${item.epochMs}" } } },
                    ) { item ->
                        HistoryCard(item)
                    }
                }
            }
            Text(
                text = "층이 다르면 눌러 주세요",
                color = MutedGray,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(14.dp))
            FloorOverrideButtons(
                selected = selectedFloor,
                onSelect = viewModel::setFloorOverride,
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    detail: String,
    pingSeq: Long,
    receiving: Boolean,
) {
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(pingSeq) {
        if (pingSeq == 0L) return@LaunchedEffect
        flashing = true
        delay(220)
        flashing = false
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardIdle)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detail,
                color = MutedGray,
                fontSize = 14.sp,
                maxLines = 2,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        ReceiveRadio(active = receiving, flashing = flashing)
    }
}

@Composable
private fun HistoryCard(item: ClosestApproach) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardIdle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = item.name.ifBlank { "비콘" },
            color = Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = BeaconDisplayFormat.distance(item.distanceMeters),
            color = Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            text = BeaconDisplayFormat.koreanDateTimeCompact(item.epochMs),
            color = MutedGray,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReceiveRadio(active: Boolean, flashing: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (flashing) 1.18f else 1f,
        animationSpec = tween(140),
        label = "radioScale",
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = 2.dp,
                color = if (active || flashing) Ink else RadioIdle,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active || flashing) {
            Box(
                modifier = Modifier
                    .size(if (flashing) 14.dp else 10.dp)
                    .clip(CircleShape)
                    .background(Ink),
            )
        }
    }
}

@Composable
private fun FloorOverrideButtons(
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FloorSelectButton(
            code = "B1",
            label = "B1",
            accent = Color(BeaconDisplayFormat.COLOR_B1),
            selected = selected == "B1",
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
        FloorSelectButton(
            code = "B2",
            label = "B2",
            accent = Color(BeaconDisplayFormat.COLOR_B2),
            selected = selected == "B2",
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
        FloorSelectButton(
            code = "1F",
            label = "1F",
            accent = Color(BeaconDisplayFormat.COLOR_1F),
            selected = selected == "1F",
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FloorSelectButton(
    code: String,
    label: String,
    accent: Color,
    selected: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        selected -> accent
        else -> CardIdle
    }
    val content = when {
        selected && code == "B2" -> Color(BeaconDisplayFormat.COLOR_B2_TEXT)
        selected -> Color.White
        else -> Ink
    }
    Box(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable { onSelect(code) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp,
        )
    }
}

private fun startScanIfReady(
    bluetoothManager: BluetoothManager?,
    viewModel: BeaconScanViewModel,
    onMessage: (String?) -> Unit,
) {
    if (!isBluetoothEnabled(bluetoothManager)) {
        onMessage("기기에서 블루투스를 켠 뒤 다시 실행하세요.")
        return
    }
    onMessage(null)
    viewModel.startScanning()
}

private fun isBluetoothEnabled(bluetoothManager: BluetoothManager?): Boolean {
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    return adapter?.isEnabled == true
}
