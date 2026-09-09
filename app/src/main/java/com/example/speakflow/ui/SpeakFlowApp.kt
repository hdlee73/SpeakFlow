package com.example.speakflow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speakflow.model.*
import com.example.speakflow.R

private val Blue = Color(0xFF285BE6)
private val Mint = Color(0xFF43C6A4)
private val Ink = Color(0xFF17243D)
private val Canvas = Color(0xFFF4F7FF)

@Composable
fun SpeakFlowApp(
    state: LearningUiState,
    settingsOpen: Boolean,
    onSettingsOpen: () -> Unit,
    onSettingsClose: () -> Unit,
    onSettingsSave: (LearningSettings) -> Unit,
    onImport: () -> Unit,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onMessageDismiss: () -> Unit
) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Canvas, surface = Color.White)) {
        Scaffold(containerColor = Canvas, snackbarHost = {
            state.message?.let { message ->
                Snackbar(modifier = Modifier.padding(16.dp), action = { TextButton(onClick = onMessageDismiss) { Text("확인") } }) { Text(message) }
            }
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val expanded = maxWidth >= 600.dp
                Column(Modifier.fillMaxSize()) {
                    TopBar(state, onImport, onSettingsOpen)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Blue, trackColor = Color(0xFFDCE5FF)
                    )
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.items.isEmpty()) EmptyState(onImport)
                        else LessonCard(state, expanded, onReplay, onRestart)
                    }
                    PlayerControls(state, onPrevious, onPlayPause, onNext)
                }
            }
        }
        if (settingsOpen) SettingsSheet(state.settings, onSettingsClose, onSettingsSave)
    }
}

@Composable
private fun TopBar(state: LearningUiState, onImport: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onImport, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("＋ 데이터", color = Ink) }
        Text("문장 말하기", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Ink)
        TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("☷ 설정", color = Ink) }
    }
}

@Composable
private fun EmptyState(onImport: () -> Unit) {
    Card(
        Modifier.padding(24.dp).widthIn(max = 440.dp),
        shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.app_icon),
                contentDescription = "SpeakFlow 앱 아이콘",
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))
            )
            Spacer(Modifier.height(16.dp))
            Text("나만의 문장으로 말하기 연습", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("첫 번째 열은 한국어, 두 번째 열은 영어인 Excel(.xlsx) 또는 CSV 파일을 불러오세요.", color = Color(0xFF667085), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onImport, shape = RoundedCornerShape(14.dp)) { Text("데이터셋 불러오기") }
        }
    }
}

@Composable
private fun LessonCard(state: LearningUiState, expanded: Boolean, onReplay: () -> Unit, onRestart: () -> Unit) {
    val item = state.current ?: return
    val translation = state.settings.mode == LearningMode.TRANSLATION
    val statusColor = when (state.phase) {
        LessonPhase.CORRECT -> Mint
        LessonPhase.TIMED_OUT -> Color(0xFFF59E0B)
        else -> Blue
    }
    Card(
        Modifier.padding(24.dp).widthIn(max = if (expanded) 520.dp else 400.dp)
            .shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = statusColor.copy(alpha = .18f)),
        shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(if (expanded) 32.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = statusColor, shape = RoundedCornerShape(9.dp)) {
                Text(statusLabel(state), Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
            if (translation && state.phase != LessonPhase.CORRECT) {
                Text(item.korean, fontSize = if (expanded) 28.sp else 23.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text("영어로 말해 보세요", color = Color(0xFF667085))
            } else {
                Text(item.english, fontSize = if (expanded) 30.sp else 25.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = if (state.phase == LessonPhase.CORRECT) Mint else Ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(item.korean, color = Color(0xFF667085), textAlign = TextAlign.Center)
            }
            if (state.phase == LessonPhase.LISTENING || state.phase == LessonPhase.RETRYING) {
                Spacer(Modifier.height(18.dp))
                Text("남은 시간 ${state.remainingSeconds}초", color = Blue, fontWeight = FontWeight.SemiBold)
            }
            if (state.heardText.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("내 발음: “${state.heardText}”  ·  ${state.score ?: 0}점", color = if (state.phase == LessonPhase.CORRECT) Mint else Color(0xFFE56B5D), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onReplay, shape = RoundedCornerShape(13.dp)) { Text("↻ 다시 듣기") }
                OutlinedButton(onClick = onRestart, shape = RoundedCornerShape(13.dp)) { Text("처음부터") }
            }
        }
    }
}

private fun statusLabel(state: LearningUiState) = when (state.phase) {
    LessonPhase.SPEAKING -> "🔊 들어 보세요"
    LessonPhase.LISTENING -> "🎙 Speak now"
    LessonPhase.CORRECT -> "✓ Nice"
    LessonPhase.RETRYING -> "한 번 더 말해 보세요"
    LessonPhase.TIMED_OUT -> "다음 문장으로 이동"
    LessonPhase.PAUSED -> "일시 정지"
    LessonPhase.COMPLETE -> "학습 완료"
    else -> "준비 완료"
}

@Composable
private fun PlayerControls(state: LearningUiState, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp, top = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        RoundButton(ControlIcon.Previous, 48, onPrevious, state.position > 0)
        Spacer(Modifier.width(14.dp))
        RoundButton(
            if (state.phase == LessonPhase.PAUSED || state.phase == LessonPhase.IDLE || state.phase == LessonPhase.COMPLETE) ControlIcon.Play else ControlIcon.Pause,
            62, onPlayPause, state.items.isNotEmpty(), primary = true
        )
        Spacer(Modifier.width(14.dp))
        RoundButton(ControlIcon.Next, 48, onNext, state.position < state.order.lastIndex)
    }
}

private enum class ControlIcon { Previous, Play, Pause, Next }

@Composable
private fun RoundButton(icon: ControlIcon, buttonSize: Int, onClick: () -> Unit, enabled: Boolean, primary: Boolean = false) {
    FilledIconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(buttonSize.dp).shadow(10.dp, CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) Color.White else Color.White, contentColor = Blue, disabledContainerColor = Color.White.copy(alpha = .7f)),
        shape = CircleShape
    ) {
        Canvas(Modifier.size(if (primary) 28.dp else 23.dp)) {
            val color = if (enabled) Blue else Color(0xFFB7BFCC)
            val stroke = Stroke(width = size.minDimension * .105f, cap = StrokeCap.Round)
            when (icon) {
                ControlIcon.Play -> {
                    val path = Path().apply {
                        moveTo(size.width * .32f, size.height * .20f)
                        lineTo(size.width * .78f, size.height * .50f)
                        lineTo(size.width * .32f, size.height * .80f)
                        close()
                    }
                    drawPath(path, color)
                }
                ControlIcon.Pause -> {
                    drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width * .36f, size.height * .23f), end = androidx.compose.ui.geometry.Offset(size.width * .36f, size.height * .77f), strokeWidth = size.width * .14f, cap = StrokeCap.Round)
                    drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width * .64f, size.height * .23f), end = androidx.compose.ui.geometry.Offset(size.width * .64f, size.height * .77f), strokeWidth = size.width * .14f, cap = StrokeCap.Round)
                }
                ControlIcon.Previous, ControlIcon.Next -> {
                    val isNext = icon == ControlIcon.Next
                    drawArc(color, startAngle = if (isNext) -70f else 160f, sweepAngle = if (isNext) 255f else -255f, useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(size.width * .18f, size.height * .18f),
                        size = androidx.compose.ui.geometry.Size(size.width * .64f, size.height * .64f), style = stroke)
                    val tipX = if (isNext) size.width * .82f else size.width * .18f
                    val direction = if (isNext) -1f else 1f
                    drawLine(color, androidx.compose.ui.geometry.Offset(tipX, size.height * .29f), androidx.compose.ui.geometry.Offset(tipX + direction * size.width * .20f, size.height * .25f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawLine(color, androidx.compose.ui.geometry.Offset(tipX, size.height * .29f), androidx.compose.ui.geometry.Offset(tipX + direction * size.width * .08f, size.height * .48f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(current: LearningSettings, onClose: () -> Unit, onSave: (LearningSettings) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("학습 설정", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(20.dp))
            Text("학습 방식", fontWeight = FontWeight.Bold)
            LearningMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.mode == mode, onClick = { draft = draft.copy(mode = mode) })
                    Column(Modifier.padding(vertical = 8.dp)) { Text(mode.label); Text(mode.description, color = Color.Gray, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("문장 순서", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayOrder.entries.forEach { order -> FilterChip(selected = draft.order == order, onClick = { draft = draft.copy(order = order) }, label = { Text(order.label) }) }
            }
            Spacer(Modifier.height(18.dp))
            Text("자동 넘김: ${draft.timeoutSeconds}초", fontWeight = FontWeight.Bold)
            Slider(value = draft.timeoutSeconds.toFloat(), onValueChange = { draft = draft.copy(timeoutSeconds = it.toInt()) }, valueRange = 5f..30f, steps = 24)
            Text("통과 기준: ${draft.passScore}점", fontWeight = FontWeight.Bold)
            Slider(value = draft.passScore.toFloat(), onValueChange = { draft = draft.copy(passScore = it.toInt()) }, valueRange = 55f..95f, steps = 7)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onSave(draft) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("설정 저장") }
        }
    }
}
