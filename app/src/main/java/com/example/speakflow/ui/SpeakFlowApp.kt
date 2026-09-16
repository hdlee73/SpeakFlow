package com.example.speakflow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speakflow.model.*
import com.example.speakflow.R
import com.example.speakflow.speech.SpeechScorer

private val Blue = Color(0xFF285BE6)
private val Mint = Color(0xFF43C6A4)
private val Ink = Color(0xFF17243D)
private val Canvas = Color(0xFFF4F7FF)

@Composable
fun SpeakFlowApp(
    state: LearningUiState,
    settingsOpen: Boolean,
    datasetsOpen: Boolean,
    onSettingsOpen: () -> Unit,
    onSettingsClose: () -> Unit,
    onSettingsSave: (LearningSettings) -> Unit,
    onDatasetsOpen: () -> Unit,
    onDatasetsClose: () -> Unit,
    onDatasetSelect: (SavedDataset) -> Unit,
    onDatasetDelete: (SavedDataset) -> Unit,
    onImport: () -> Unit,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
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
                    TopBar(state, onDatasetsOpen, onSettingsOpen)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Blue, trackColor = Color(0xFFDCE5FF)
                    )
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.items.isEmpty()) EmptyState(onImport)
                        else LessonCard(state, expanded, onReplay, onRestart, onRetry, onNext)
                    }
                    PlayerControls(state, onPrevious, onPlayPause, onNext)
                }
            }
        }
        if (settingsOpen) SettingsSheet(state.settings, onSettingsClose, onSettingsSave)
        if (datasetsOpen) DatasetSheet(state, onDatasetsClose, onDatasetSelect, onDatasetDelete, onImport)
    }
}

@Composable
private fun TopBar(state: LearningUiState, onImport: () -> Unit, onSettings: () -> Unit) {
    Surface(color = Color.White.copy(alpha = .72f), shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = onImport, contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(painterResource(R.drawable.ic_database), null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("데이터")
            }
            Text("문장 말하기", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            FilledTonalButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("설정")
            }
        }
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
            Text("첫 두 열에 한국어와 영어가 있는 Excel(.xlsx) 또는 CSV 파일을 불러오세요. 열 순서는 자동으로 인식합니다.", color = Color(0xFF667085), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onImport, shape = RoundedCornerShape(14.dp)) { Text("데이터셋 불러오기") }
        }
    }
}

@Composable
private fun LessonCard(state: LearningUiState, expanded: Boolean, onReplay: () -> Unit, onRestart: () -> Unit, onRetry: () -> Unit, onNext: () -> Unit) {
    val item = state.current ?: return
    val translation = state.settings.mode == LearningMode.TRANSLATION
    val statusColor = when (state.phase) {
        LessonPhase.CORRECT -> Mint
        LessonPhase.TIMED_OUT -> Color(0xFFF59E0B)
        else -> Blue
    }
    Card(
        Modifier.padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = if (expanded) 560.dp else 420.dp).fillMaxHeight(.96f)
            .shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = statusColor.copy(alpha = .18f)),
        shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (expanded) 32.dp else 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LessonStatusHeader(state, statusColor)
            Text(state.microphoneLabel, color = Color(0xFF667085), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val revealEnglish = !translation || state.phase == LessonPhase.CORRECT || state.phase == LessonPhase.TIMED_OUT
                if (!revealEnglish) {
                    val (fontSize, lineHeight) = adaptiveTextSize(item.korean.length, expanded)
                    Text(item.korean, fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(maskedEnglishHint(item.english), fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF8A94A6), textAlign = TextAlign.Center)
                } else {
                    val (fontSize, lineHeight) = adaptiveTextSize(item.english.length, expanded)
                    RealtimeSentence(item.english, state.liveText, fontSize, lineHeight)
                    Spacer(Modifier.height(8.dp))
                    Text(item.korean, fontSize = if (item.korean.length > 70) 12.sp else 14.sp, lineHeight = 19.sp, color = Color(0xFF667085), textAlign = TextAlign.Center)
                }
                state.score?.let { score ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = if (score >= state.settings.passScore) Mint.copy(alpha = .16f) else Color(0xFFFFE8CC),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "직전 발음 점수  ${score}점",
                            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            color = if (score >= state.settings.passScore) Color(0xFF087F5B) else Color(0xFFB45309),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                }
                if (state.phase == LessonPhase.LISTENING || state.phase == LessonPhase.RETRYING) {
                    Spacer(Modifier.height(10.dp))
                    Text("남은 시간 ${state.remainingSeconds}초", color = Blue, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            when (state.phase) {
                LessonPhase.CORRECT, LessonPhase.RETRYING, LessonPhase.TIMED_OUT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("다시 발음") }
                    Button(onClick = onNext, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                        Text(when {
                            state.hasAnotherRepeat -> "다음 반복"
                            state.position == state.order.lastIndex -> "학습 완료"
                            else -> "다음 문장"
                        })
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onReplay, shape = RoundedCornerShape(13.dp)) { Text("다시 듣기") }
                    OutlinedButton(onClick = onRestart, shape = RoundedCornerShape(13.dp)) { Text("처음부터") }
                }
            }
        }
    }
}

@Composable
private fun LessonStatusHeader(state: LearningUiState, statusColor: Color) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.width(160.dp).fillMaxHeight(),
            color = statusColor,
            shape = RoundedCornerShape(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(statusLabel(state), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(94.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (state.settings.repeatCount > 1) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Blue.copy(alpha = .10f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("반복 ${state.repeatNumber}/${state.settings.repeatCount}", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun RealtimeSentence(expected: String, liveText: String, fontSize: androidx.compose.ui.unit.TextUnit, lineHeight: androidx.compose.ui.unit.TextUnit) {
    val words = SpeechScorer.displayWords(expected)
    val matched = SpeechScorer.matchedWords(expected, liveText)
    val styled = buildAnnotatedString {
        words.forEachIndexed { index, word ->
            if (index > 0) append(" ")
            withStyle(SpanStyle(color = if (matched.getOrElse(index) { false }) Blue else Color(0xFFBFC2C7), fontWeight = if (matched.getOrElse(index) { false }) FontWeight.ExtraBold else FontWeight.Bold)) {
                append(word)
            }
        }
    }
    Text(styled, fontSize = fontSize, lineHeight = lineHeight, textAlign = TextAlign.Center)
}

private fun maskedEnglishHint(english: String): String {
    val words = SpeechScorer.displayWords(english)
    return words.mapIndexed { index, word ->
        val hide = if (words.size <= 2) index == words.lastIndex else index % 3 == 1
        if (hide) word.map { char -> if (char.isLetterOrDigit()) '_' else char }.joinToString("") else word
    }.joinToString(" ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatasetSheet(
    state: LearningUiState,
    onClose: () -> Unit,
    onSelect: (SavedDataset) -> Unit,
    onDelete: (SavedDataset) -> Unit,
    onImport: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<SavedDataset?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("내 데이터셋", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("저장된 파일을 선택하면 바로 학습할 수 있어요.", color = Color(0xFF667085), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            state.savedDatasets.forEach { dataset ->
                Surface(
                    onClick = { onSelect(dataset) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (dataset.id == state.activeDatasetId) Blue.copy(alpha = .10f) else Color(0xFFF5F7FA)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_database), null, tint = Blue)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dataset.name, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text("${dataset.sentenceCount}개 문장", fontSize = 12.sp, color = Color(0xFF667085))
                        }
                        if (dataset.id == state.activeDatasetId) Text("학습 중", color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        TextButton(
                            onClick = { pendingDelete = dataset },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD92D20)),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("삭제", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            if (state.savedDatasets.isEmpty()) Text("아직 저장된 데이터셋이 없습니다.", Modifier.padding(vertical = 20.dp), color = Color.Gray)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onImport, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("새 데이터셋 추가") }
        }
    }
    pendingDelete?.let { dataset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("데이터셋 삭제") },
            text = { Text("‘${dataset.name}’ 데이터셋을 삭제할까요? 삭제한 파일은 복구할 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { onDelete(dataset); pendingDelete = null }) {
                    Text("삭제", color = Color(0xFFD92D20), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } }
        )
    }
}

private fun statusLabel(state: LearningUiState) = when (state.phase) {
    LessonPhase.SPEAKING -> "🔊 들어 보세요"
    LessonPhase.LISTENING -> "🎙 Speak now"
    LessonPhase.CORRECT -> when {
        state.hasAnotherRepeat -> "✓ 통과 · 다음 반복"
        state.settings.autoAdvanceSentence -> "✓ 통과 · 자동 이동"
        else -> "✓ Nice"
    }
    LessonPhase.RETRYING -> "한 번 더 말해 보세요"
    LessonPhase.TIMED_OUT -> "다음 문장으로 이동"
    LessonPhase.PAUSED -> "일시 정지"
    LessonPhase.COMPLETE -> "학습 완료"
    else -> "준비 완료"
}

private fun adaptiveTextSize(length: Int, expanded: Boolean) = when {
    length <= 45 -> (if (expanded) 30.sp else 26.sp) to (if (expanded) 37.sp else 33.sp)
    length <= 90 -> (if (expanded) 25.sp else 22.sp) to (if (expanded) 31.sp else 28.sp)
    length <= 150 -> (if (expanded) 21.sp else 18.sp) to (if (expanded) 27.sp else 23.sp)
    else -> (if (expanded) 18.sp else 15.sp) to (if (expanded) 23.sp else 20.sp)
}

@Composable
private fun PlayerControls(state: LearningUiState, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp, top = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        RoundButton(R.drawable.ic_previous, "이전 문장", 48, onPrevious, state.position > 0)
        Spacer(Modifier.width(14.dp))
        RoundButton(
            if (state.phase == LessonPhase.PAUSED || state.phase == LessonPhase.IDLE || state.phase == LessonPhase.COMPLETE) R.drawable.ic_play else R.drawable.ic_pause,
            if (state.phase == LessonPhase.PAUSED || state.phase == LessonPhase.IDLE || state.phase == LessonPhase.COMPLETE) "재생" else "일시 정지",
            62, onPlayPause, state.items.isNotEmpty(), primary = true
        )
        Spacer(Modifier.width(14.dp))
        RoundButton(R.drawable.ic_next, "다음 문장", 48, onNext, state.position <= state.order.lastIndex)
    }
}

@Composable
private fun RoundButton(iconRes: Int, description: String, buttonSize: Int, onClick: () -> Unit, enabled: Boolean, primary: Boolean = false) {
    FilledIconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.size(buttonSize.dp).shadow(10.dp, CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (primary) Color.White else Color.White, contentColor = Blue, disabledContainerColor = Color.White.copy(alpha = .7f)),
        shape = CircleShape
    ) { Icon(painterResource(iconRes), description, Modifier.size(if (primary) 28.dp else 23.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(current: LearningSettings, onClose: () -> Unit, onSave: (LearningSettings) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).navigationBarsPadding().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
                Text("문장 반복 횟수", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { count ->
                        FilterChip(
                            selected = draft.repeatCount == count,
                            onClick = { draft = draft.copy(repeatCount = count) },
                            label = { Text("${count}회") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("통과 후 다음 문장", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (draft.autoAdvanceSentence) "자동으로 이동" else "버튼을 눌러 수동 이동")
                        Text("반복 연습 중에는 통과하면 다음 반복으로 자동 이동합니다.", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = draft.autoAdvanceSentence,
                        onCheckedChange = { draft = draft.copy(autoAdvanceSentence = it) }
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text("발음 제한 시간: ${draft.timeoutSeconds}초", fontWeight = FontWeight.Bold)
                Slider(value = draft.timeoutSeconds.toFloat(), onValueChange = { draft = draft.copy(timeoutSeconds = it.toInt()) }, valueRange = 5f..30f, steps = 24)
                Text("통과 기준: ${draft.passScore}점", fontWeight = FontWeight.Bold)
                Slider(value = draft.passScore.toFloat(), onValueChange = { draft = draft.copy(passScore = it.toInt()) }, valueRange = 55f..95f, steps = 7)
                Spacer(Modifier.height(12.dp))
            }
            Surface(shadowElevation = 10.dp, color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
                    Button(onClick = { onSave(draft) }, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("설정 저장하기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
