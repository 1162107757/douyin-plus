package com.example.gesturefeed;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

/** Small reusable 16 kHz microphone reader for enrollment and recognition. */
public final class VoiceRecorder implements AutoCloseable {
    private static final String TAG = "GestureFeed";
    public interface Listener {
        void onSamples(short[] samples);
        void onError(String message);
    }

    private final Listener listener;
    private AudioRecord recorder;
    private AcousticEchoCanceler echoCanceler;
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
            int[] sources = {
                    // The communication path is the one most devices wire to
                    // the hardware echo reference.
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
            };
            for (int source : sources) {
                try {
                    AudioRecord candidate = new AudioRecord(source,
                            VoiceFeatureExtractor.SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize);
                    if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                        recorder = candidate;
                        Log.i(TAG, "voice recorder source=" + source);
                        break;
                    }
                    candidate.release();
                } catch (IllegalArgumentException ignored) {
                    // Try the next source on devices that do not expose this
                    // input profile.
                }
            }
            if (recorder == null) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(recorder.getAudioSessionId());
                    if (echoCanceler != null) {
                        echoCanceler.setEnabled(true);
                        Log.i(TAG, "acoustic echo canceler enabled=" + echoCanceler.getEnabled());
                    }
                } catch (RuntimeException ignored) {
                    echoCanceler = null;
                    Log.w(TAG, "acoustic echo canceler unavailable");
                }
            } else {
                Log.i(TAG, "acoustic echo canceler not supported");
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
        if (echoCanceler != null) {
            try {
                echoCanceler.setEnabled(false);
            } catch (RuntimeException ignored) {
            }
            echoCanceler.release();
            echoCanceler = null;
        }
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
