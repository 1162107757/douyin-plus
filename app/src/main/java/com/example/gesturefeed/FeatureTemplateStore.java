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

/**
 * Small on-device template store. Only numeric features are persisted; raw
 * camera frames and microphone samples are deliberately discarded.
 */
public final class FeatureTemplateStore {
    private static final int MAX_SAMPLES = 5;
    private final SharedPreferences preferences;

    public FeatureTemplateStore(Context context, String preferenceName) {
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
    }

    public synchronized List<float[]> load(ControlDirection direction) {
        String encoded = preferences.getString(direction.name(), null);
        if (encoded == null) return new ArrayList<>();
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int count = input.readInt();
            if (count < 0 || count > MAX_SAMPLES) return new ArrayList<>();
            List<float[]> result = new ArrayList<>();
            for (int sample = 0; sample < count; sample++) {
                int length = input.readInt();
                if (length <= 0 || length > 20000) return new ArrayList<>();
                float[] values = new float[length];
                for (int index = 0; index < length; index++) values[index] = input.readFloat();
                result.add(values);
            }
            return result;
        } catch (IllegalArgumentException | IOException ignored) {
            return new ArrayList<>();
        }
    }

    public synchronized int count(ControlDirection direction) {
        return load(direction).size();
    }

    public synchronized void addSample(ControlDirection direction, float[] sample) {
        if (sample == null || sample.length == 0) return;
        List<float[]> samples = load(direction);
        if (samples.size() >= MAX_SAMPLES) samples.remove(0);
        samples.add(sample.clone());
        save(direction, samples);
    }

    public synchronized void clear(ControlDirection direction) {
        preferences.edit().remove(direction.name()).apply();
    }

    private void save(ControlDirection direction, List<float[]> samples) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(samples.size());
            for (float[] sample : samples) {
                output.writeInt(sample.length);
                for (float value : sample) output.writeFloat(value);
            }
            output.flush();
            String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
            preferences.edit().putString(direction.name(), encoded).apply();
        } catch (IOException ignored) {
            // ByteArrayOutputStream does not throw in normal operation.
        }
    }
}
