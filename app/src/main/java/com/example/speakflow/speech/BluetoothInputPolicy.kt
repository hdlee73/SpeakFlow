package com.example.speakflow.speech

object BluetoothInputPolicy {
    fun isWatch(name: String, deviceClass: Int? = null): Boolean = deviceClass == 0x0704 ||
        Regex("watch|워치|fitbit|garmin|amazfit|wrist", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
