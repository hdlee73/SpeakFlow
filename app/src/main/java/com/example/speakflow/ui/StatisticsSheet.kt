package com.example.speakflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speakflow.model.DailyLearning
import java.time.LocalDate
import java.time.temporal.WeekFields

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsSheet(days: List<DailyLearning>, onClose: () -> Unit) {
    var period by remember { mutableStateOf("오늘") }
    val today = LocalDate.now()
    val week = WeekFields.ISO
    fun key(date: LocalDate): String = when (period) {
        "주간" -> "${date.get(week.weekBasedYear())}-W${date.get(week.weekOfWeekBasedYear()).toString().padStart(2, '0')}"
        "월별" -> date.toString().take(7)
        "연간" -> date.year.toString()
        "전체" -> "전체"
        else -> date.toString()
    }
    val filtered = if (period == "오늘") days.filter { it.date == today.toString() } else days
    val groups = filtered.groupBy { key(LocalDate.parse(it.date)) }.toSortedMap(compareByDescending { it })
    fun duration(seconds: Long) = "${seconds / 3600}시간 ${(seconds % 3600) / 60}분 ${seconds % 60}초"
    ModalBottomSheet(onDismissRequest = onClose, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(24.dp).verticalScroll(rememberScrollState())) {
            Text("학습 통계", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("학습량은 정답 또는 시간 종료로 끝난 발음 시도 수입니다. 반복과 재시도도 포함하며, 건너뛴 문장은 제외합니다. 시간은 예문 듣기·발음 중만 누적됩니다.", fontSize = 12.sp)
            listOf(listOf("오늘", "일별", "주간"), listOf("월별", "연간", "전체")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { label -> FilterChip(selected = period == label, onClick = { period = label }, label = { Text(label) }) }
                }
            }
            Text("${filtered.sumOf { it.attempts }}회 · 정답 ${filtered.sumOf { it.correct }}회", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(duration(filtered.sumOf { it.seconds }), fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            if (groups.isEmpty()) Text("아직 학습 기록이 없습니다.")
            groups.forEach { (label, rows) ->
                HorizontalDivider()
                Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("${rows.sumOf { it.attempts }}회 · 정답 ${rows.sumOf { it.correct }}회 · ${duration(rows.sumOf { it.seconds })}", modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}
