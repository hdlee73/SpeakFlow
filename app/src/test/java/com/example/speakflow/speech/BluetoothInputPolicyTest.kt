package com.example.speakflow.speech
import org.junit.Assert.*
import org.junit.Test
class BluetoothInputPolicyTest {
    @Test fun excludesWatchesByNameAndDeviceClass() {
        assertTrue(BluetoothInputPolicy.isWatch("Galaxy Watch8"))
        assertTrue(BluetoothInputPolicy.isWatch("갤럭시 워치"))
        assertTrue(BluetoothInputPolicy.isWatch("Wearable", 0x0704))
        assertTrue(BluetoothInputPolicy.isWatch("Garmin Venu"))
        assertFalse(BluetoothInputPolicy.isWatch("AirPods Pro"))
        assertFalse(BluetoothInputPolicy.isWatch("Galaxy Buds"))
    }
}
