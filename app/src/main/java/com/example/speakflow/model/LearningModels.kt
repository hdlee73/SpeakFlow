package com.example.speakflow.model

data class SentencePair(val korean: String, val english: String)

data class SavedDataset(
    val id: String,
    val name: String,
    val fileName: String,
    val sentenceCount: Int
)

enum class LearningMode(val label: String, val description: String) {
    SHADOWING("영어 듣고 따라 말하기", "영어 예문을 듣고 그대로 말해요"),
    TRANSLATION("한국어 듣고 영어 말하기", "한국어 뜻을 듣고 영어로 말해요")
}

enum class PlayOrder(val label: String) {
    SEQUENTIAL("순서대로"), RANDOM("무작위")
}

enum class VoiceAccent(val label: String) { US("미국"), UK("영국") }

enum class VoiceGender(val label: String) { FEMALE("여성"), MALE("남성") }

enum class LessonPhase {
    IDLE, SPEAKING, LISTENING, CORRECT, RETRYING, TIMED_OUT, PAUSED, COMPLETE
}

data class LearningSettings(
    val mode: LearningMode = LearningMode.SHADOWING,
    val order: PlayOrder = PlayOrder.SEQUENTIAL,
    val repeatCount: Int = 1,
    val autoAdvanceSentence: Boolean = false,
    val timeoutSeconds: Int = 20,
    val passScore: Int = 78,
    val mirrorAudio: Boolean = false,
    val voiceAccent: VoiceAccent = VoiceAccent.US,
    val voiceGender: VoiceGender = VoiceGender.FEMALE
)

data class LearningUiState(
    val items: List<SentencePair> = emptyList(),
    val order: List<Int> = emptyList(),
    val position: Int = 0,
    val phase: LessonPhase = LessonPhase.IDLE,
    val settings: LearningSettings = LearningSettings(),
    val datasetName: String? = null,
    val activeDatasetId: String? = null,
    val savedDatasets: List<SavedDataset> = emptyList(),
    val heardText: String = "",
    val liveText: String = "",
    val retryText: String? = null,
    val matchedWords: List<Boolean> = emptyList(),
    val score: Int? = null,
    val allWordsMatched: Boolean = false,
    val feedbackSequence: Long = 0L,
    val feedbackSuccess: Boolean? = null,
    val listenRequestId: Long = 0L,
    val microphoneLabel: String = "휴대전화 마이크",
    val remainingSeconds: Int = 0,
    val statistics: List<DailyLearning> = emptyList(),
    val message: String? = null
) {
    val current: SentencePair? get() = order.getOrNull(position)?.let(items::getOrNull)
    val progress: Float get() = if (order.isEmpty()) 0f else (position + 1f) / order.size
    val repeatNumber: Int get() = if (order.isEmpty()) 0 else position % settings.repeatCount.coerceAtLeast(1) + 1
    val hasAnotherRepeat: Boolean get() = order.getOrNull(position) == order.getOrNull(position + 1)
}

data class DailyLearning(val date: String, val attempts: Int = 0, val correct: Int = 0, val seconds: Long = 0)
