package com.example.speakflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speakflow.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatasetEditor(dataset: SavedDataset, rows: List<SentencePair>, onClose: () -> Unit,
    onSave: (Int, String, String) -> Unit) {
    var query by remember(dataset.id) { mutableStateOf("") }
    var editing by remember(dataset.id) { mutableStateOf<Int?>(null) }
    var english by remember { mutableStateOf("") }
    var korean by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(20.dp)) {
            Text("데이터셋 문장 편집", fontSize = 22.sp)
            Text(dataset.name, fontSize = 12.sp)
            OutlinedTextField(query, { query = it }, label = { Text("영어·해석 검색") }, modifier = Modifier.fillMaxWidth())
            Text("수정한 내용은 앱에 저장됩니다. 원본 파일은 유지됩니다.", fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
                    if (query.isBlank() || row.english.contains(query, true) || row.korean.contains(query, true)) {
                        ListItem(headlineContent = { Text("${index + 1}. ${row.english}") },
                            supportingContent = { Text(row.korean) }, trailingContent = {
                                TextButton(onClick = { editing = index; english = row.english; korean = row.korean }) { Text("수정") }
                            })
                        HorizontalDivider()
                    }
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
        }
    }
    editing?.let { index ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("문장 ${index + 1} 수정") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(english, { english = it }, label = { Text("영어 문장") })
                OutlinedTextField(korean, { korean = it }, label = { Text("한국어 해석") })
            } }, confirmButton = { TextButton(enabled = english.isNotBlank(), onClick = {
                onSave(index, korean, english); editing = null
            }) { Text("저장") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } })
    }
}
