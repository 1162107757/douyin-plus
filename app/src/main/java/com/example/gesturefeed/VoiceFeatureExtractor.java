package com.example.gesturefeed;

/** Lightweight local speech feature extraction without a network recognizer. */
public final class VoiceFeatureExtractor {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SIZE = 320;
    public static final int HOP_SIZE = 160;
    public static final int FEATURE_SIZE = 10;
    public static final int RESAMPLED_FRAMES = 48;

    private VoiceFeatureExtractor() {
    }

    /** Returns a variable-length sequence of log-energy, zero-crossing and spectral-band features. */
    public static float[] extract(short[] samples) {
        if (samples == null || samples.length < FRAME_SIZE) return null;
        int start = 0;
        int end = samples.length;
        int peak = 1;
        for (short sample : samples) peak = Math.max(peak, Math.abs((int) sample));
        // Keep quiet but valid speech. The recognizer already applies a
        // short-time VAD gate; using 180 here used to trim low-volume words
        // down to almost nothing before feature extraction.
        int gate = Math.max(40, peak / 16);
        while (start < end && Math.abs(samples[start]) < gate) start++;
        while (end > start && Math.abs(samples[end - 1]) < gate) end--;
        if (end - start < FRAME_SIZE / 2) return null;
        int frameCount = Math.max(1, 1 + Math.max(0, end - start - FRAME_SIZE) / HOP_SIZE);
        float[] result = new float[frameCount * FEATURE_SIZE];
        for (int frame = 0; frame < frameCount; frame++) {
            int offset = frame * FEATURE_SIZE;
            int base = Math.min(end - FRAME_SIZE, start + frame * HOP_SIZE);
            float energy = 0f;
            int zeroCrossings = 0;
            float previous = 0f;
            for (int index = 0; index < FRAME_SIZE; index++) {
                float value = samples[base + index] / 32768f;
                float window = 0.54f - 0.46f * (float) Math.cos(2 * Math.PI * index / (FRAME_SIZE - 1));
                value *= window;
                energy += value * value;
                if (index > 0 && ((value >= 0f) != (previous >= 0f))) zeroCrossings++;
                previous = value;
            }
            result[offset] = (float) Math.log(1e-5 + Math.sqrt(energy / FRAME_SIZE));
            result[offset + 1] = zeroCrossings / (float) FRAME_SIZE;
            // Eight coarse spectral bands are enough for speaker-specific
            // command templates and are cheap enough for continuous capture.
            for (int band = 0; band < 8; band++) {
                int firstBin = 2 + band * 7;
                int lastBin = firstBin + 7;
                float bandEnergy = 0f;
                for (int bin = firstBin; bin <= lastBin; bin++) {
                    float real = 0f;
                    float imaginary = 0f;
                    for (int index = 0; index < FRAME_SIZE; index += 2) {
                        float value = samples[base + index] / 32768f;
                        float window = 0.54f - 0.46f * (float) Math.cos(2 * Math.PI * index / (FRAME_SIZE - 1));
                        double angle = 2 * Math.PI * bin * index / FRAME_SIZE;
                        real += value * window * (float) Math.cos(angle);
                        imaginary -= value * window * (float) Math.sin(angle);
                    }
                    bandEnergy += real * real + imaginary * imaginary;
                }
                result[offset + 2 + band] = (float) Math.log(1e-5 + bandEnergy);
            }
        }
        return result;
    }

    /** Resamples and standardizes the variable-length sequence for matching. */
    public static float[] prepareSequence(float[] raw) {
        if (raw == null || raw.length < FEATURE_SIZE * 3 || raw.length % FEATURE_SIZE != 0) return null;
        int sourceFrames = raw.length / FEATURE_SIZE;
        float[] result = new float[RESAMPLED_FRAMES * FEATURE_SIZE];
        for (int frame = 0; frame < RESAMPLED_FRAMES; frame++) {
            float position = frame * (sourceFrames - 1f) / (RESAMPLED_FRAMES - 1f);
            int left = Math.min(sourceFrames - 1, (int) Math.floor(position));
            int right = Math.min(sourceFrames - 1, left + 1);
            float fraction = position - left;
            for (int index = 0; index < FEATURE_SIZE; index++) {
                result[frame * FEATURE_SIZE + index] = raw[left * FEATURE_SIZE + index]
                        + (raw[right * FEATURE_SIZE + index] - raw[left * FEATURE_SIZE + index]) * fraction;
            }
        }
        for (int index = 0; index < FEATURE_SIZE; index++) {
            float mean = 0f;
            for (int frame = 0; frame < RESAMPLED_FRAMES; frame++) mean += result[frame * FEATURE_SIZE + index];
            mean /= RESAMPLED_FRAMES;
            float variance = 0f;
            for (int frame = 0; frame < RESAMPLED_FRAMES; frame++) {
                float delta = result[frame * FEATURE_SIZE + index] - mean;
                variance += delta * delta;
            }
            float standardDeviation = (float) Math.sqrt(variance / RESAMPLED_FRAMES);
            standardDeviation = Math.max(0.02f, standardDeviation);
            for (int frame = 0; frame < RESAMPLED_FRAMES; frame++) {
                result[frame * FEATURE_SIZE + index] =
                        (result[frame * FEATURE_SIZE + index] - mean) / standardDeviation;
            }
        }
        return result;
    }

    /**
     * Builds a phrase-independent speaker fingerprint from normalized
     * spectral-band ratios. Only the mean and spread of voiced frames are
     * retained, so no raw microphone audio is persisted.
     */
    public static float[] speakerSignature(short[] samples) {
        if (samples == null || samples.length < FRAME_SIZE * 3) return null;
        int start = 0;
        int end = samples.length;
        int peak = 1;
        for (short sample : samples) peak = Math.max(peak, Math.abs((int) sample));
        int gate = Math.max(60, peak / 16);
        while (start < end && Math.abs(samples[start]) < gate) start++;
        while (end > start && Math.abs(samples[end - 1]) < gate) end--;
        if (end - start < FRAME_SIZE * 2) return null;

        final int featureCount = 9; // eight band ratios + zero crossing rate
        float[] sum = new float[featureCount];
        float[] sumSquares = new float[featureCount];
        int voicedFrames = 0;
        int frameCount = 1 + Math.max(0, end - start - FRAME_SIZE) / HOP_SIZE;
        float voiceGate = Math.max(0.0025f, peak / 32768f / 14f);
        for (int frame = 0; frame < frameCount; frame++) {
            int base = Math.min(end - FRAME_SIZE, start + frame * HOP_SIZE);
            float energy = 0f;
            int zeroCrossings = 0;
            float previous = 0f;
            for (int index = 0; index < FRAME_SIZE; index++) {
                float value = samples[base + index] / 32768f;
                float window = 0.54f - 0.46f * (float) Math.cos(
                        2 * Math.PI * index / (FRAME_SIZE - 1));
                value *= window;
                energy += value * value;
                if (index > 0 && ((value >= 0f) != (previous >= 0f))) zeroCrossings++;
                previous = value;
            }
            float frameRms = (float) Math.sqrt(energy / FRAME_SIZE);
            if (frameRms < voiceGate) continue;

            float[] features = new float[featureCount];
            float totalBandEnergy = 0f;
            for (int band = 0; band < 8; band++) {
                int firstBin = 2 + band * 7;
                int lastBin = firstBin + 7;
                float bandEnergy = 0f;
                for (int bin = firstBin; bin <= lastBin; bin++) {
                    float real = 0f;
                    float imaginary = 0f;
                    for (int index = 0; index < FRAME_SIZE; index += 2) {
                        float value = samples[base + index] / 32768f;
                        float window = 0.54f - 0.46f * (float) Math.cos(
                                2 * Math.PI * index / (FRAME_SIZE - 1));
                        double angle = 2 * Math.PI * bin * index / FRAME_SIZE;
                        real += value * window * (float) Math.cos(angle);
                        imaginary -= value * window * (float) Math.sin(angle);
                    }
                    bandEnergy += real * real + imaginary * imaginary;
                }
                features[band] = bandEnergy;
                totalBandEnergy += bandEnergy;
            }
            totalBandEnergy = Math.max(1e-8f, totalBandEnergy);
            for (int band = 0; band < 8; band++) {
                features[band] = (float) Math.log(1e-4 + features[band] / totalBandEnergy);
            }
            features[8] = zeroCrossings / (float) FRAME_SIZE;
            for (int index = 0; index < featureCount; index++) {
                sum[index] += features[index];
                sumSquares[index] += features[index] * features[index];
            }
            voicedFrames++;
        }
        // A single short frame is too easy to confuse with a click, music
        // transient or playback leakage. Require a small voiced window before
        // producing a speaker fingerprint.
        if (voicedFrames < 5) return null;
        float[] profile = new float[VoiceSpeakerProfileStore.PROFILE_SIZE];
        for (int index = 0; index < featureCount; index++) {
            float mean = sum[index] / voicedFrames;
            float variance = Math.max(0f,
                    sumSquares[index] / voicedFrames - mean * mean);
            profile[index] = mean;
            profile[featureCount + index] = (float) Math.sqrt(variance);
        }
        return profile;
    }

    public static float distance(float[] first, float[] second) {
        if (first == null || second == null || first.length != second.length) return Float.MAX_VALUE;
        float total = 0f;
        int frames = first.length / FEATURE_SIZE;
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * FEATURE_SIZE;
            float frameDistance = 0f;
            for (int index = 0; index < FEATURE_SIZE; index++) {
                float delta = first[offset + index] - second[offset + index];
                frameDistance += delta * delta;
            }
            total += (float) Math.sqrt(frameDistance / FEATURE_SIZE);
        }
        return total / Math.max(1, frames);
    }
}
