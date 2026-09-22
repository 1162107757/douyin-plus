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
    private static final float NOISE_RMS_RATIO = 1.90f;
    private static final float NOISE_PEAK_RATIO = 1.35f;
    private static final float END_RMS_RATIO = 1.30f;
    private static final float END_PEAK_RATIO = 1.25f;
    private static final long NOISE_WARMUP_MS = 350L;
    private static final int START_FRAMES_REQUIRED = 3;
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
    private static final float MIN_MATCH_MARGIN = 0.18f;
    // Strict speaker verification. The nearest enrolled sample handles normal
    // utterance variation; the centroid check prevents one noisy enrollment
    // sample from becoming an accidental pass for background audio.
    private static final float SPEAKER_MATCH_THRESHOLD = 1.80f;
    private static final float SPEAKER_CENTROID_THRESHOLD = 2.10f;
    private static final int MIN_VOICED_FRAMES = 4;

    private final FeatureTemplateStore store;
    private final VoiceSpeakerProfileStore speakerProfileStore;
    private final Listener listener;
    private final List<Short> samples = new ArrayList<>();
    private final ArrayDeque<short[]> preRoll = new ArrayDeque<>();
    private long startedAt;
    private long lastSpeechAt;
    private long cooldownUntil;
    private boolean waitingRelease;
    private long listenStartedAt;
    private float noiseRms = INITIAL_NOISE_RMS;
    private float noisePeak = INITIAL_NOISE_PEAK;
    private int consecutiveVoiceFrames;
    private int consecutiveQuietFrames;
    private int voiceFrames;

    public VoiceCommandRecognizer(FeatureTemplateStore store,
                                  VoiceSpeakerProfileStore speakerProfileStore,
                                  Listener listener) {
        this.store = store;
        this.speakerProfileStore = speakerProfileStore;
        this.listener = listener;
    }

    public synchronized void reset() {
        samples.clear();
        preRoll.clear();
        startedAt = 0L;
        lastSpeechAt = 0L;
        cooldownUntil = 0L;
        waitingRelease = false;
        listenStartedAt = 0L;
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
        if (listenStartedAt == 0L) listenStartedAt = now;
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
            if (!speech) updateNoiseFloor(rms, peak, false);
            if (now >= cooldownUntil) {
                waitingRelease = false;
                consecutiveVoiceFrames = 0;
                consecutiveQuietFrames = 0;
                if (!speech) listener.onState("自定义声音 · 准备下一次");
            }
            if (waitingRelease) return;
        }
        if (startedAt == 0L) {
            // Give the adaptive floor a short settling period after the
            // recorder starts.  This prevents a loud room from becoming the
            // first false command immediately after entering the mode.
            if (now - listenStartedAt < NOISE_WARMUP_MS) {
                primeNoiseFloor(rms, peak);
                rememberPreRoll(chunk);
                consecutiveVoiceFrames = 0;
                return;
            }
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
        boolean relativeSilence = isRelativeSilence(rms, peak);
        if (!speech && relativeSilence) updateNoiseFloor(rms, peak, false);
        if (speech) {
            lastSpeechAt = now;
            voiceFrames++;
            consecutiveQuietFrames = 0;
        } else if (relativeSilence) {
            consecutiveQuietFrames++;
        } else {
            // Residual environment sound is not silence, but it also is not
            // a new voiced frame.  Keep waiting until the short window ends.
            consecutiveQuietFrames = 0;
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
        if (voiceFrames < MIN_VOICED_FRAMES) {
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
        if (speakerProfileStore.count() < VoiceSpeakerProfileStore.MIN_SAMPLES) {
            listener.onState("自定义声音 · 请先完成 "
                    + VoiceSpeakerProfileStore.MIN_SAMPLES + " 次音色学习");
            return;
        }
        float[] speakerSignature = VoiceFeatureExtractor.speakerSignature(pcm);
        if (speakerSignature == null) {
            listener.onState("自定义声音 · 音色特征不足，请清晰说完整口令");
            return;
        }
        List<float[]> speakerProfiles = speakerProfileStore.load();
        float speakerDistance = nearestSpeakerDistance(speakerSignature, speakerProfiles);
        float speakerCentroidDistance = speakerCentroidDistance(speakerSignature, speakerProfiles);
        boolean speakerMismatch = speakerDistance > SPEAKER_MATCH_THRESHOLD
                || speakerCentroidDistance > SPEAKER_CENTROID_THRESHOLD;
        if (speakerMismatch) {
            listener.onState("自定义声音 · 音色不匹配，请本人重新说");
            Log.d(TAG, "speaker not matched nearest=" + speakerDistance
                    + " centroid=" + speakerCentroidDistance
                    + " samples=" + pcm.length);
            return;
        }
        ControlDirection bestDirection = null;
        float bestDistance = Float.MAX_VALUE;
        float secondBestDistance = Float.MAX_VALUE;
        int templateCount = 0;
        ControlDirection[] directions = ControlDirection.values();
        float[] directionDistances = new float[directions.length];
        java.util.Arrays.fill(directionDistances, Float.MAX_VALUE);
        for (int directionIndex = 0; directionIndex < directions.length; directionIndex++) {
            ControlDirection direction = directions[directionIndex];
            for (float[] template : store.load(direction)) {
                templateCount++;
                float distance = VoiceFeatureExtractor.distance(sequence, template);
                directionDistances[directionIndex] = Math.min(directionDistances[directionIndex], distance);
            }
        }
        // Compare directions, not individual enrollment samples. Otherwise a
        // second sample from the same direction would look like a competing
        // class and make every repeated enrollment appear ambiguous.
        for (int directionIndex = 0; directionIndex < directions.length; directionIndex++) {
            float distance = directionDistances[directionIndex];
            if (distance < bestDistance) {
                secondBestDistance = bestDistance;
                bestDistance = distance;
                bestDirection = directions[directionIndex];
            } else if (distance < secondBestDistance) {
                secondBestDistance = distance;
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
        boolean weakCommand = bestDistance > MATCH_THRESHOLD || ambiguous;
        if (weakCommand || now < cooldownUntil) {
            listener.onState("自定义声音 · 未匹配，请再说一次");
            Log.d(TAG, "voice command not matched distance=" + bestDistance
                    + " second=" + secondBestDistance + " ambiguous=" + ambiguous
                    + " speakerDistance=" + speakerDistance
                    + " speakerCentroid=" + speakerCentroidDistance
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

    private float nearestSpeakerDistance(float[] candidate, List<float[]> profiles) {
        if (candidate == null) return Float.MAX_VALUE;
        float nearest = Float.MAX_VALUE;
        for (float[] profile : profiles) {
            if (profile == null || profile.length != candidate.length) continue;
            float total = 0f;
            for (int index = 0; index < candidate.length; index++) {
                float delta = candidate[index] - profile[index];
                total += delta * delta;
            }
            nearest = Math.min(nearest, (float) Math.sqrt(total / candidate.length));
        }
        return nearest;
    }

    private float speakerCentroidDistance(float[] candidate, List<float[]> profiles) {
        if (candidate == null || profiles == null || profiles.isEmpty()) return Float.MAX_VALUE;
        float[] centroid = new float[candidate.length];
        int valid = 0;
        for (float[] profile : profiles) {
            if (profile == null || profile.length != candidate.length) continue;
            for (int index = 0; index < candidate.length; index++) centroid[index] += profile[index];
            valid++;
        }
        if (valid == 0) return Float.MAX_VALUE;
        float total = 0f;
        for (int index = 0; index < candidate.length; index++) {
            centroid[index] /= valid;
            float delta = candidate[index] - centroid[index];
            total += delta * delta;
        }
        return (float) Math.sqrt(total / candidate.length);
    }

    private boolean isVoice(float rms, int peak) {
        float rmsGate = Math.max(RMS_THRESHOLD, noiseRms * NOISE_RMS_RATIO);
        float peakGate = Math.max(PEAK_THRESHOLD, noisePeak * NOISE_PEAK_RATIO);
        // RMS must rise above the local floor.  A peak or a stronger RMS
        // excursion then confirms a voiced onset instead of a steady hum.
        return rms >= rmsGate && (peak >= peakGate || rms >= noiseRms * 2.50f);
    }

    private boolean isRelativeSilence(float rms, int peak) {
        return rms <= noiseRms * END_RMS_RATIO
                && peak <= noisePeak * END_PEAK_RATIO;
    }

    private void updateNoiseFloor(float rms, int peak, boolean voice) {
        if (voice) return;
        // Track only a bounded value so a short loud transient does not make
        // the next spoken command impossible to start.
        float limitedRms = Math.min(rms, Math.max(RMS_THRESHOLD, noiseRms * 2.0f));
        float limitedPeak = Math.min(peak, Math.max(PEAK_THRESHOLD, noisePeak * 2.0f));
        noiseRms = noiseRms * 0.92f + limitedRms * 0.08f;
        noisePeak = noisePeak * 0.92f + limitedPeak * 0.08f;
    }

    private void primeNoiseFloor(float rms, int peak) {
        // During the initial warmup it is safe to learn the room level more
        // quickly; no command is accepted in this interval.
        noiseRms = noiseRms * 0.65f + Math.min(rms, 12000f) * 0.35f;
        noisePeak = noisePeak * 0.65f + Math.min(peak, 24000f) * 0.35f;
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
