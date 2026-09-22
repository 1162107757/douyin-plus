package com.example.gesturefeed;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Stores only local speaker-feature vectors, never raw microphone samples. */
public final class VoiceSpeakerProfileStore {
    public static final int PROFILE_SIZE = 18;
    /** Multiple enrollment utterances are needed to model normal voice variation. */
    public static final int MIN_SAMPLES = 5;
    private static final int MAX_SAMPLES = 10;
    private static final String KEY_PROFILE = "profile";

    private final SharedPreferences preferences;

    public VoiceSpeakerProfileStore(Context context) {
        preferences = context.getSharedPreferences("custom_voice_speaker", Context.MODE_PRIVATE);
    }

    public synchronized List<float[]> load() {
        String encoded = preferences.getString(KEY_PROFILE, null);
        if (encoded == null) return new ArrayList<>();
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int count = input.readInt();
            if (count < 0 || count > MAX_SAMPLES) return new ArrayList<>();
            List<float[]> result = new ArrayList<>();
            for (int sample = 0; sample < count; sample++) {
                int length = input.readInt();
                if (length != PROFILE_SIZE) return new ArrayList<>();
                float[] values = new float[length];
                for (int index = 0; index < length; index++) values[index] = input.readFloat();
                result.add(values);
            }
            return result;
        } catch (IllegalArgumentException | IOException ignored) {
            return new ArrayList<>();
        }
    }

    public synchronized int count() {
        return load().size();
    }

    public synchronized void addSample(float[] profile) {
        if (profile == null || profile.length != PROFILE_SIZE) return;
        List<float[]> samples = load();
        if (samples.size() >= MAX_SAMPLES) samples.remove(0);
        samples.add(profile.clone());
        save(samples);
    }

    public synchronized void clear() {
        preferences.edit().remove(KEY_PROFILE).apply();
    }

    private void save(List<float[]> samples) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(samples.size());
            for (float[] sample : samples) {
                output.writeInt(sample.length);
                for (float value : sample) output.writeFloat(value);
            }
            output.flush();
            preferences.edit().putString(KEY_PROFILE,
                    Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)).apply();
        } catch (IOException ignored) {
            // ByteArrayOutputStream does not throw in normal operation.
        }
    }
}
