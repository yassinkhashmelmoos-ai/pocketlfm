package com.thunder.agi.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _transcribedText = MutableStateFlow<String?>(null)
    val transcribedText: StateFlow<String?> = _transcribedText

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun startRecording(onFinished: (File?) -> Unit) {
        if (!hasPermission()) {
            Log.e(TAG, "No microphone permission")
            onFinished(null)
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 4
        )

        val outputFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
        _isRecording.value = true

        recordingJob = scope.launch {
            val audioData = mutableListOf<Short>()
            val buffer = ShortArray(bufferSize)

            audioRecord?.startRecording()

            while (isActive && _isRecording.value) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    audioData.addAll(buffer.take(read))
                }
            }

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Write WAV file
            writeWavFile(outputFile, audioData.toShortArray(), SAMPLE_RATE)
            withContext(Dispatchers.Main) {
                onFinished(outputFile)
            }
        }
    }

    fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
    }

    private fun writeWavFile(file: File, audioData: ShortArray, sampleRate: Int) {
        val dataSize = audioData.size * 2
        val totalSize = dataSize + 36

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)  // PCM
            header.putShort(1)  // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())

            val dataBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in audioData) dataBuffer.putShort(sample)
            fos.write(dataBuffer.array())
        }
    }

    // Simple energy-based transcription placeholder.
    // The light_vad_coeff.bin file can be used here for proper VAD in a future native integration.
    // For now we use Android's SpeechRecognizer via the ViewModel.
    fun release() {
        scope.cancel()
        audioRecord?.release()
        audioRecord = null
    }
}
