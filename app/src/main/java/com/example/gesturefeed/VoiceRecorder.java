package com.example.gesturefeed;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;

/** Small reusable 16 kHz microphone reader for enrollment and recognition. */
public final class VoiceRecorder implements AutoCloseable {
    public interface Listener {
        void onSamples(short[] samples);
        void onError(String message);
    }

    private final Listener listener;
    private AudioRecord recorder;
    private NoiseSuppressor noiseSuppressor;
    private Thread thread;
    private volatile boolean running;

    public VoiceRecorder(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean start() {
        if (running) return true;
        int minimum = AudioRecord.getMinBufferSize(VoiceFeatureExtractor.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            listener.onError("麦克风不可用");
            return false;
        }
        try {
            int bufferSize = Math.max(minimum * 2, 4096);
            try {
                // VOICE_RECOGNITION asks the platform for the speech-input
                // path, which usually includes vendor echo/noise processing.
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        VoiceFeatureExtractor.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
            } catch (IllegalArgumentException unsupportedSource) {
                // A few older devices expose only the normal microphone path.
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        VoiceFeatureExtractor.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
            }
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(recorder.getAudioSessionId());
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                } catch (RuntimeException ignored) {
                    noiseSuppressor = null;
                }
            }
            recorder.startRecording();
            running = true;
            thread = new Thread(this::readLoop, "gesture-voice");
            thread.start();
            return true;
        } catch (RuntimeException error) {
            releaseRecorder();
            listener.onError("麦克风启动失败");
            return false;
        }
    }

    private void readLoop() {
        short[] buffer = new short[VoiceFeatureExtractor.HOP_SIZE * 2];
        while (running && recorder != null) {
            int count;
            try {
                count = recorder.read(buffer, 0, buffer.length);
            } catch (RuntimeException error) {
                listener.onError("麦克风读取失败");
                break;
            }
            if (count <= 0) continue;
            short[] samples = new short[count];
            System.arraycopy(buffer, 0, samples, 0, count);
            listener.onSamples(samples);
        }
    }

    public synchronized void stop() {
        running = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        Thread current = thread;
        thread = null;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(300L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        releaseRecorder();
    }

    private synchronized void releaseRecorder() {
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.setEnabled(false);
            } catch (RuntimeException ignored) {
            }
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
