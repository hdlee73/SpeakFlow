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
private val Miss = Color(0xFFE5484D)

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
    onDatasetEdit: (SavedDataset) -> Unit,
    onEditorClose: () -> Unit,
    onSentenceSave: (Int, String, String) -> Unit,
    onVoicePreview: (String) -> Unit,
    onOpenUpdate: () -> Unit,
    onDatasetSequence: (List<String>) -> Unit,
    onImport: () -> Unit,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onRetry: () -> Unit,
    onMessageDismiss: () -> Unit
) {
    var statisticsOpen by remember { mutableStateOf(false) }
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
        if (settingsOpen) SettingsSheet(state.settings, state.voices, state.voiceLabel, onVoicePreview, onOpenUpdate, onSettingsClose, onSettingsSave) { statisticsOpen = true }
        if (state.editingDataset != null) DatasetEditor(state.editingDataset, state.editingItems, onEditorClose, onSentenceSave)
        if (statisticsOpen) StatisticsSheet(state.statistics) { statisticsOpen = false }
        if (datasetsOpen) DatasetSheet(state, onDatasetsClose, onDatasetSelect, onDatasetDelete, onImport, onDatasetSequence, onDatasetEdit)
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
            Text("한글·영어 두 열 또는 영어 한 열로 된 Excel(.xlsx)·CSV 파일을 불러오세요. 열 순서는 자동으로 인식합니다.", color = Color(0xFF667085), textAlign = TextAlign.Center)
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
        LessonPhase.TIMED_OUT, LessonPhase.RETRYING -> Color(0xFFF59E0B)
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
            Text("문장 ${state.position / state.settings.repeatCount + 1}/${state.items.size} · 반복 ${state.repeatNumber}/${state.settings.repeatCount}", fontSize = 12.sp, color = Blue)
            Text(state.microphoneLabel, color = Color(0xFF667085), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val revealEnglish = !translation || item.korean.isBlank() || !state.retryText.isNullOrBlank() || state.phase in setOf(
                    LessonPhase.RETRYING, LessonPhase.CORRECT, LessonPhase.TIMED_OUT
                )
                if (!revealEnglish) {
                    val (fontSize, lineHeight) = adaptiveTextSize(item.korean.length, expanded)
                    Text(item.korean, fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
                } else {
                    val (fontSize, lineHeight) = adaptiveTextSize(item.english.length, expanded)
                    RealtimeSentence(
                        item.english, state.liveText, state.matchedWords, fontSize, lineHeight,
                        strictness = state.settings.strictness,
                        highlightMisses = state.phase == LessonPhase.RETRYING
                    )
                    val showTranslation = item.korean.isNotBlank() && state.phase in setOf(
                        LessonPhase.RETRYING, LessonPhase.CORRECT, LessonPhase.TIMED_OUT
                    )
                    if (showTranslation) {
                        Spacer(Modifier.height(8.dp))
                        Text(item.korean, fontSize = if (item.korean.length > 70) 12.sp else 14.sp, lineHeight = 19.sp, color = Color(0xFF667085), textAlign = TextAlign.Center)
                    }
                }
                if (state.phase == LessonPhase.LISTENING && !state.retryText.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("다시 말할 부분: ${state.retryText}", fontSize = 16.sp, color = Miss, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("이 부분만 다시 말해도 됩니다.", fontSize = 12.sp, color = Color.Gray)
                }
                state.score?.let {
                    if (state.heardText.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "인식: ${state.heardText}",
                            color = Color(0xFF667085),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                if (state.phase == LessonPhase.LISTENING) {
                    Spacer(Modifier.height(10.dp))
                    Text("남은 시간 ${state.remainingSeconds}초", color = Blue, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            when (state.phase) {
                LessonPhase.CORRECT, LessonPhase.RETRYING, LessonPhase.TIMED_OUT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("다시 발음") }
                    Button(onClick = onNext, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) {
                        Text(if (state.position == state.order.lastIndex) "학습 완료" else "다음 문장")
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
            modifier = Modifier.fillMaxWidth(.82f).fillMaxHeight(),
            color = statusColor,
            shape = RoundedCornerShape(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    statusLabel(state),
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun RealtimeSentence(
    expected: String,
    liveText: String,
    confirmedMatches: List<Boolean>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    strictness: RecognitionStrictness,
    highlightMisses: Boolean
) {
    val words = SpeechScorer.displayWords(expected)
    val liveMatches = SpeechScorer.matchedWords(expected, liveText, strictness)
    val matched = words.indices.map { index ->
        confirmedMatches.getOrElse(index) { false } || liveMatches.getOrElse(index) { false }
    }
    val styled = buildAnnotatedString {
        words.forEachIndexed { index, word ->
            if (index > 0) append(" ")
            val hit = matched.getOrElse(index) { false }
            val color = when {
                hit -> Blue
                highlightMisses -> Miss
                else -> Color(0xFFBFC2C7)
            }
            withStyle(SpanStyle(color = color, fontWeight = if (hit) FontWeight.ExtraBold else FontWeight.Bold)) {
                append(word)
            }
        }
    }
    Text(styled, fontSize = fontSize, lineHeight = lineHeight, textAlign = TextAlign.Center)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatasetSheet(
    state: LearningUiState,
    onClose: () -> Unit,
    onSelect: (SavedDataset) -> Unit,
    onDelete: (SavedDataset) -> Unit,
    onImport: () -> Unit,
    onSequence: (List<String>) -> Unit,
    onEdit: (SavedDataset) -> Unit
) {
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<SavedDataset?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("내 데이터셋", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("저장된 파일을 선택하면 바로 학습할 수 있어요.", color = Color(0xFF667085), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Text("이어 학습할 파일을 체크하세요. 체크한 순서대로 이어집니다.", fontSize = 12.sp)
            Button(onClick = { onSequence(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("선택한 ${selected.size}개 데이터셋 이어 학습")
            }
            state.savedDatasets.forEach { dataset ->
                Surface(
                    onClick = { onSelect(dataset) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = if (dataset.id == state.activeDatasetId) Blue.copy(alpha = .10f) else Color(0xFFF5F7FA)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dataset.id in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + dataset.id else selected - dataset.id
                        })
                        if (dataset.id in selected) Text("${selected.indexOf(dataset.id) + 1}", color = Blue)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dataset.name, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text("${dataset.sentenceCount}개 문장", fontSize = 12.sp, color = Color(0xFF667085))
                        }
                        if (dataset.id == state.activeDatasetId) Text("학습 중", color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        TextButton(onClick = { onEdit(dataset) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("편집") }
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
        state.allWordsMatched -> "✓ 모두 인식"
        else -> "✓ Nice"
    }
    LessonPhase.RETRYING -> if (state.settings.autoAdvanceSentence) "20초 종료 · 다음으로 이동" else "20초 종료 · 다시 / 다음 선택"
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
    Surface(
        modifier = Modifier.size(buttonSize.dp),
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 10.dp
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(
                painterResource(iconRes), description,
                Modifier.size(if (primary) 28.dp else 23.dp),
                tint = if (enabled) Blue else Color(0xFFB9BDC6)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(current: LearningSettings, voices: List<InstalledVoice>, voiceLabel: String, onPreview: (String) -> Unit, onOpenUpdate: () -> Unit, onClose: () -> Unit, onSave: (LearningSettings) -> Unit, onStatistics: () -> Unit) {
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
                Text("발음 판정", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecognitionStrictness.entries.forEach { level ->
                        FilterChip(selected = draft.strictness == level, onClick = { draft = draft.copy(strictness = level) }, label = { Text(level.label) })
                    }
                }
                Text(draft.strictness.description, color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Text("영어 음성", fontWeight = FontWeight.Bold)
                Text("발음 지역", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceAccent.entries.forEach { accent ->
                        FilterChip(selected = draft.voiceAccent == accent, onClick = { draft = draft.copy(voiceAccent = accent, voiceId = "") }, label = { Text(accent.label) })
                    }
                }
                Text("목소리", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceGender.entries.forEach { gender ->
                        FilterChip(selected = draft.voiceGender == gender, onClick = { draft = draft.copy(voiceGender = gender, voiceId = "") }, label = { Text(gender.label) })
                    }
                }
                Text("엔진이 성별을 제공하지 않으면 남성·여성을 자동 확정할 수 없습니다. 미리듣기로 실제 음성을 선택하세요.", color = Color.Gray, fontSize = 12.sp)
                if (voiceLabel.isNotBlank()) Text("현재 음성: $voiceLabel", color = Color.Gray, fontSize = 11.sp)
                var voicesOpen by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { voicesOpen = !voicesOpen }, modifier = Modifier.fillMaxWidth()) { Text("실제 음성 선택 · 미리듣기") }
                if (voicesOpen) {
                    TextButton(onClick = { draft = draft.copy(voiceId = "") }) { Text("자동 선택") }
                    val country = if (draft.voiceAccent == VoiceAccent.US) "US" else "GB"
                    val regional = voices.filter { it.country == country }
                    if (regional.isEmpty()) Text("이 지역 음성이 설치되어 있지 않습니다.", color = Color.Gray)
                    regional.forEach { voice ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = draft.voiceId == voice.id, onClick = { draft = draft.copy(voiceId = voice.id) })
                            Text(voice.label, Modifier.weight(1f), fontSize = 11.sp)
                            TextButton(onClick = { onPreview(voice.id) }) { Text("듣기") }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("야외 잡음 보정 입력", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = draft.outdoorAudio, onCheckedChange = { draft = draft.copy(outdoorAudio = it) })
                }
                Text("Android 13 이상에서 지원되는 음성 인식 엔진에 보정된 마이크 입력을 전달합니다. 지원되지 않으면 일반 입력으로 돌아갑니다. 바람 소리 제거 효과는 기기마다 다릅니다.", fontSize = 12.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("휴대전화 마이크 사용", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = draft.phoneMic, onCheckedChange = { draft = draft.copy(phoneMic = it) })
                }
                Text("끄면 블루투스 이어셋을 우선 사용합니다. 워치는 마이크 대상으로 제외합니다.", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(18.dp))
                Text("동일 문장 반복횟수", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { count -> FilterChip(selected = draft.repeatCount == count,
                        onClick = { draft = draft.copy(repeatCount = count) }, label = { Text("${count}회") }) }
                }
                Text("정답 또는 시간 종료 후 설정한 횟수만큼 반복합니다. 다음 버튼·Enter는 반복을 건너뜁니다.", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Text("다음 문장 전환", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.autoAdvanceSentence, onClick = { draft = draft.copy(autoAdvanceSentence = true) })
                    Text("자동: 정답 또는 20초 종료 시 이동", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !draft.autoAdvanceSentence, onClick = { draft = draft.copy(autoAdvanceSentence = false) })
                    Text("수동: 정답은 자동, 오답은 다시/다음 선택", fontSize = 13.sp)
                }
                Text("제한시간 20초 · 정답일 때만 알림음", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("scrcpy PC 미러링 오디오", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = draft.mirrorAudio, onCheckedChange = { draft = draft.copy(mirrorAudio = it) })
                }
                Text("미러링 중 미디어 음량을 유지합니다. PC에서 scrcpy --audio-dup 으로 실행하면 휴대전화·PC에서 함께 들을 수 있습니다. 인식 서비스의 시작음이 들릴 수 있습니다.", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = onStatistics, modifier = Modifier.fillMaxWidth()) { Text("학습량 · 학습시간 통계") }
                OutlinedButton(onClick = onOpenUpdate, modifier = Modifier.fillMaxWidth()) { Text("새 버전 확인 · 업데이트 받기") }
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
