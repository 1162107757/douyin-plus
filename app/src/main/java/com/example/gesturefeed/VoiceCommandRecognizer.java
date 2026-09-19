package com.example.gesturefeed;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** VAD + local feature-template matcher for four user-recorded voice commands. */
public final class VoiceCommandRecognizer implements VoiceRecorder.Listener {
    public interface Listener {
        void onGesture(ControlDirection direction);
        void onState(String state);
    }

    private static final String TAG = "GestureFeed";
    // The recorder delivers 640 samples at 16 kHz (about 40 ms) per chunk.
    // Require a short, consistent onset before buffering; this filters clicks
    // and the first transient of music without making speech feel laggy.
    private static final int PEAK_THRESHOLD = 220;
    private static final float RMS_THRESHOLD = 58f;
    private static final float INITIAL_NOISE_RMS = 30f;
    private static final float INITIAL_NOISE_PEAK = 120f;
    private static final float NOISE_RMS_RATIO = 1.80f;
    private static final float NOISE_PEAK_RATIO = 1.35f;
    private static final int START_FRAMES_REQUIRED = 2;
    private static final int END_SILENCE_FRAMES = 4;
    private static final int PRE_ROLL_CHUNKS = 2;
    private static final long MIN_COMMAND_MS = 220L;
    private static final long MAX_COMMAND_MS = 1200L;
    private static final long COOLDOWN_MS = 650L;
    private static final int MIN_TEMPLATE_COUNT = 1;
    // Voice timbre and volume vary between utterances.  The previous values
    // were tuned too tightly and rejected valid re-speakings of a learned
    // command.  Keep a bounded distance gate, but leave enough room for
    // natural variation while the template set is still small.
    private static final float MATCH_THRESHOLD = 2.80f;
    // Background media audio can look like a valid command in the feature
    // space.  Require a clear winner instead of firing on the nearest class.
    private static final float MIN_MATCH_MARGIN = 0.12f;

    private final FeatureTemplateStore store;
    private final Listener listener;
    private final List<Short> samples = new ArrayList<>();
    private final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
    private long startedAt;
    private long lastSpeechAt;
    private long cooldownUntil;
    private boolean waitingRelease;
    private float noiseRms = INITIAL_NOISE_RMS;
    private float noisePeak = INITIAL_NOISE_PEAK;
    private int consecutiveVoiceFrames;
    private int consecutiveQuietFrames;
    private int voiceFrames;

    public VoiceCommandRecognizer(FeatureTemplateStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    public synchronized void reset() {
        samples.clear();
        preRoll.clear();
        startedAt = 0L;
        lastSpeechAt = 0L;
        cooldownUntil = 0L;
        waitingRelease = false;
        noiseRms = INITIAL_NOISE_RMS;
        noisePeak = INITIAL_NOISE_PEAK;
        consecutiveVoiceFrames = 0;
        consecutiveQuietFrames = 0;
        voiceFrames = 0;
    }

    @Override
    public synchronized void onSamples(short[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        long now = System.currentTimeMillis();
        int peak = 0;
        long energy = 0L;
        for (short sample : chunk) {
            int value = Math.abs((int) sample);
            peak = Math.max(peak, value);
            energy += (long) value * value;
        }
        float rms = (float) Math.sqrt(energy / (double) chunk.length);
        boolean speech = isVoice(rms, peak);
        if (waitingRelease) {
            // The microphone often has a non-zero noise floor, so waiting
            // forever for a completely silent chunk makes recognition one-shot.
            // Re-arm after the short cooldown; a new voiced chunk can begin
            // the next command immediately.
            updateNoiseFloor(rms, peak, false);
            if (now >= cooldownUntil) {
                waitingRelease = false;
                consecutiveVoiceFrames = 0;
                consecutiveQuietFrames = 0;
                if (!speech) listener.onState("自定义声音 · 准备下一次");
            }
            if (waitingRelease) return;
        }
        if (startedAt == 0L) {
            updateNoiseFloor(rms, peak, speech);
            rememberPreRoll(chunk);
            if (speech) consecutiveVoiceFrames++;
            else consecutiveVoiceFrames = 0;
            if (consecutiveVoiceFrames < START_FRAMES_REQUIRED) return;
            startedAt = now - Math.max(0L, (preRoll.size() - 1L) * chunkDurationMs(chunk));
            listener.onState("自定义声音 · 聆听中");
            voiceFrames = consecutiveVoiceFrames;
            consecutiveQuietFrames = 0;
            for (short[] block : preRoll) append(block);
            preRoll.clear();
            lastSpeechAt = now;
            return;
        }
        append(chunk);
        if (speech) {
            lastSpeechAt = now;
            voiceFrames++;
            consecutiveQuietFrames = 0;
        } else {
            consecutiveQuietFrames++;
        }
        if (now - startedAt >= MAX_COMMAND_MS
                || (now - startedAt >= MIN_COMMAND_MS
                && consecutiveQuietFrames >= END_SILENCE_FRAMES)) finish(now);
    }

    @Override
    public synchronized void onError(String message) {
        listener.onState(message);
    }

    private void finish(long now) {
        if (voiceFrames < START_FRAMES_REQUIRED) {
            samples.clear();
            startedAt = 0L;
            lastSpeechAt = 0L;
            consecutiveQuietFrames = 0;
            voiceFrames = 0;
            return;
        }
        if (samples.isEmpty()) {
            startedAt = 0L;
            return;
        }
        short[] pcm = new short[samples.size()];
        for (int index = 0; index < samples.size(); index++) pcm[index] = samples.get(index);
        samples.clear();
        startedAt = 0L;
        lastSpeechAt = 0L;
        consecutiveQuietFrames = 0;
        voiceFrames = 0;
        float[] raw = VoiceFeatureExtractor.extract(pcm);
        float[] sequence = VoiceFeatureExtractor.prepareSequence(raw);
        if (sequence == null) {
            listener.onState("自定义声音 · 语音太短，请重新说");
            return;
        }
        ControlDirection bestDirection = null;
        float bestDistance = Float.MAX_VALUE;
        float secondBestDistance = Float.MAX_VALUE;
        int templateCount = 0;
        for (ControlDirection direction : ControlDirection.values()) {
            for (float[] template : store.load(direction)) {
                templateCount++;
                float distance = VoiceFeatureExtractor.distance(sequence, template);
                if (distance < bestDistance) {
                    secondBestDistance = bestDistance;
                    bestDistance = distance;
                    bestDirection = direction;
                } else if (distance < secondBestDistance) {
                    secondBestDistance = distance;
                }
            }
        }
        // Commands are independent.  A user may only need one or two
        // directions, so do not block recognition until all four are trained.
        if (bestDirection == null || templateCount < MIN_TEMPLATE_COUNT) {
            listener.onState("自定义声音 · 请先完成至少一个方向学习");
            return;
        }
        boolean ambiguous = secondBestDistance < Float.MAX_VALUE
                && secondBestDistance - bestDistance < MIN_MATCH_MARGIN;
        if (bestDistance > MATCH_THRESHOLD || ambiguous || now < cooldownUntil) {
            listener.onState("自定义声音 · 未匹配，请再说一次");
            Log.d(TAG, "voice command not matched distance=" + bestDistance
                    + " second=" + secondBestDistance + " ambiguous=" + ambiguous
                    + " peakSamples=" + pcm.length);
            return;
        }
        waitingRelease = true;
        cooldownUntil = now + COOLDOWN_MS;
        Log.d(TAG, "voice command matched direction=" + bestDirection
                + " distance=" + bestDistance + " samples=" + pcm.length);
        listener.onState("已识别 · 自定义声音" + bestDirection.getLabel());
        listener.onGesture(bestDirection);
    }

    private boolean isVoice(float rms, int peak) {
        float rmsGate = Math.max(RMS_THRESHOLD, noiseRms * NOISE_RMS_RATIO);
        float peakGate = Math.max(PEAK_THRESHOLD, noisePeak * NOISE_PEAK_RATIO);
        // RMS must rise above the local floor.  A peak or a stronger RMS
        // excursion then confirms a voiced onset instead of a steady hum.
        return rms >= rmsGate && (peak >= peakGate || rms >= noiseRms * 2.50f);
    }

    private void updateNoiseFloor(float rms, int peak, boolean voice) {
        if (voice) return;
        // Track only a bounded value so a short loud transient does not make
        // the next spoken command impossible to start.
        float limitedRms = Math.min(rms, Math.max(RMS_THRESHOLD, noiseRms * 2.0f));
        float limitedPeak = Math.min(peak, Math.max(PEAK_THRESHOLD, noisePeak * 2.0f));
        noiseRms = noiseRms * 0.96f + limitedRms * 0.04f;
        noisePeak = noisePeak * 0.96f + limitedPeak * 0.04f;
    }

    private void rememberPreRoll(short[] chunk) {
        preRoll.addLast(chunk.clone());
        while (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.removeFirst();
    }

    private void append(short[] chunk) {
        for (short sample : chunk) samples.add(sample);
    }

    private long chunkDurationMs(short[] chunk) {
        return Math.max(1L, Math.round(chunk.length * 1000.0 / VoiceFeatureExtractor.SAMPLE_RATE));
    }
}
