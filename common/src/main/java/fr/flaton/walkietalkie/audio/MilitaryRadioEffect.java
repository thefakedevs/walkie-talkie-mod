package fr.flaton.walkietalkie.audio;

import java.util.Random;
import java.util.function.Consumer;

/**
 * Military radio effect with voice processing and noise.
 * Beeps are triggered via callbacks for external sound playback.
 */
public class MilitaryRadioEffect {
    
    private static final int SAMPLE_RATE = 48000; // Voice chat sample rate
    
    // Детектор голоса (огибающая + гистерезис)
    private static final float ENV_ATTACK_MS = 10f;
    private static final float ENV_RELEASE_MS = 100f;
    private static final float ENV_ATTACK_COEF = (float)Math.exp(-1.0 / (SAMPLE_RATE * (ENV_ATTACK_MS / 1000f)));
    private static final float ENV_RELEASE_COEF = (float)Math.exp(-1.0 / (SAMPLE_RATE * (ENV_RELEASE_MS / 1000f)));
    private static final float VOICE_START_THRESHOLD = 0.0045f; // 0.45%
    private static final float VOICE_STOP_THRESHOLD  = 0.0025f; // 0.25%
    private static final int SILENCE_SAMPLES = (int)(0.3f * SAMPLE_RATE); // 300ms
    
    // Порог компрессора
    private static final float COMP_THRESHOLD = 0.03f;
    
    // Distance сглаживание
    private static final float DIST_SMOOTH = 0.2f; // 20% к целевому значению за буфер

    private final Random random = new Random();
    
    // State tracking
    private boolean isTransmitting = false;
    private int silenceCounter = 0; // накапливаем КОЛИЧЕСТВО СЭМПЛОВ тишины

    // Envelope detector state
    private float env = 0f;
    
    // Distance smoothing state
    private float smoothedDistance = 0f;
    private boolean distanceInited = false;

    // Bandpass filter state - отдельные для каждого канала
    private float lowpassPrev = 0f;
    private float highpassPrev = 0f;
    private float highpassInput = 0f;

    // Noise coloration filter states (независимы от голосовых фильтров)
    private float noiseHpPrev = 0f;
    private float noiseHpInput = 0f;
    private float noiseLpPrev = 0f;
    private static final float NOISE_HP_CUTOFF = 300f;  // убираем басы
    private static final float NOISE_LP_CUTOFF = 4000f; // ограничиваем ВЧ
    
    // Beep at transmission end
    private boolean endBeepEnabled = true; // было константой; теперь управляется сеттером
    private int beepSamplesRemaining = 0;
    private int beepTotalSamples = 0;
    private float beepPhase = 0f;
    private static final float BEEP_FREQ_HZ = 1200f;
    private static final int BEEP_MS = 70;      // общая длительность
    private static final int BEEP_ATTACK_MS = 5;
    private static final int BEEP_DECAY_MS = 45; // остаток будет коротким релизом
    private static final float BEEP_LEVEL = 0.30f; // 30% от полной шкалы до клиппинга

    // Callbacks for sound events
    private Consumer<Float> onTransmissionStart; // параметр: distance
    private Consumer<Float> onTransmissionEnd;   // параметр: distance
    
    /**
     * Устанавливает callback для начала передачи (для воспроизведения звука включения)
     */
    public void setOnTransmissionStart(Consumer<Float> callback) {
        this.onTransmissionStart = callback;
    }
    
    /**
     * Устанавливает callback для конца передачи (для воспроизведения звука выключения)
     */
    public void setOnTransmissionEnd(Consumer<Float> callback) {
        this.onTransmissionEnd = callback;
    }
    
    /**
     * Управляет внутренним завершающим бипом в аудиопотоке.
     * По умолчанию включён. Отключите, если используете внешний колбэк onTransmissionEnd для проигрывания звука.
     */
    public void setEndBeepEnabled(boolean enabled) {
        this.endBeepEnabled = enabled;
    }

    public short[] process(short[] input, float distance) {
        if (input == null || input.length == 0) return input;
        
        // Сглаживаем distance, чтобы не было скачков параметров
        if (!distanceInited) {
            smoothedDistance = distance;
            distanceInited = true;
        } else {
            smoothedDistance += DIST_SMOOTH * (distance - smoothedDistance);
        }
        
        short[] output = new short[input.length];
        boolean hasVoice = updateEnvelopeAndDetect(input);
        
        // State machine: начало передачи
        if (hasVoice && !isTransmitting) {
            isTransmitting = true;
            silenceCounter = 0;
            
            // Сбрасываем состояние фильтров для чистого старта
            lowpassPrev = 0f;
            highpassPrev = 0f;
            highpassInput = 0f;
            
            // Триггер звука включения
            if (onTransmissionStart != null) {
                onTransmissionStart.accept(smoothedDistance);
            }
        }
        
        // Обработка и микширование
        for (int i = 0; i < input.length; i++) {
            float sample = input[i] / 32768.0f;
            
            // Основная обработка голоса
            if (isTransmitting) {
                sample = processSample(sample, smoothedDistance, hasVoice);
            }
            
            // Микшируем завершающий бип (при наличии)
            if (beepSamplesRemaining > 0 && endBeepEnabled) {
                sample += nextBeepSample();
            }
            
            // Жесткий clipping перед квантованием
            sample = Math.max(-0.95f, Math.min(0.95f, sample));
            output[i] = (short)Math.max(-32768, Math.min(32767, sample * 32768.0f));
        }
        
        // Детект конца передачи
        if (!hasVoice && isTransmitting) {
            // Важно: накапливаем количество СЭМПЛОВ тишины, а не буферов
            silenceCounter += input.length;
            if (silenceCounter >= SILENCE_SAMPLES) {
                isTransmitting = false;
                silenceCounter = 0;
                
                // Старт локального бипа
                if (endBeepEnabled) {
                    startEndBeep();
                }
                
                // Триггер звука выключения (внешний колбэк)
                if (onTransmissionEnd != null) {
                    onTransmissionEnd.accept(smoothedDistance);
                }
            }
        } else if (hasVoice) {
            silenceCounter = 0;
        }
        
        return output;
    }
    
    // --- Детектор голоса на огибающей (с гистерезисом) ---
    private boolean updateEnvelopeAndDetect(short[] input) {
        for (short s : input) {
            float x = Math.abs(s / 32768.0f);
            if (x > env) {
                env = ENV_ATTACK_COEF * env + (1 - ENV_ATTACK_COEF) * x;
            } else {
                env = ENV_RELEASE_COEF * env + (1 - ENV_RELEASE_COEF) * x;
            }
        }
        // Гистерезис: разные пороги для старта/остановки
        if (isTransmitting) {
            return env > VOICE_STOP_THRESHOLD;
        } else {
            return env > VOICE_START_THRESHOLD;
        }
    }
    

    private float processSample(float sample, float distance, boolean hasVoice) {
        // 1. Bandpass EQ - жесткий, узкий диапазон как в настоящей рации
        float cutoffHigh = 3500f - distance * 1500f; // 3.5kHz -> 2kHz (очень узко)
        float cutoffLow  =  400f + distance *  300f; // 400Hz -> 700Hz

        // Ограничиваем диапазоны и обеспечиваем зазор между НЧ и ВЧ
        cutoffLow = clamp(cutoffLow, 200f, 1000f);
        cutoffHigh = clamp(cutoffHigh, 1500f, 4000f);
        if (cutoffHigh < cutoffLow + 200f) {
            cutoffHigh = cutoffLow + 200f;
        }

        sample = applyBandpass(sample, cutoffLow, cutoffHigh);
        
        // 2. Жесткий distortion
        float drive = 8f + distance * 4f; // 8dB -> 16dB
        sample = applyDistortion(sample, drive);
        
        // 3. Компрессия с жёстким порогом (усреднённая огибающая уже сглаживает уровень)
        float ratio = 6f + distance * 6f; // 6:1 -> 12:1
        sample = applyCompressor(sample, ratio);
        
        // 4. Заметный, но окрашенный фоновый шум
        float noiseLevel = 0.01f + distance * 0.15f; // 1% -> 16%
        float noise = (random.nextFloat() * 2 - 1) * noiseLevel;
        noise = colorizeNoise(noise);

        // Ducking: шум падает когда есть голос, но остается слышимым
        if (hasVoice) {
            noise *= 0.4f;
        }
        
        sample += noise * 0.35f;
        
        return sample;
    }
    
    private float applyBandpass(float sample, float lowCutoff, float highCutoff) {
        // Highpass filter - убираем низкие частоты
        float alpha_high = (float)Math.exp(-2.0 * Math.PI * lowCutoff / SAMPLE_RATE);
        float highpassOut = alpha_high * (highpassPrev + sample - highpassInput);
        highpassInput = sample;
        highpassPrev = highpassOut;
        sample = highpassOut;
        
        // Lowpass filter - убираем высокие частоты
        float alpha_low = (float)Math.exp(-2.0 * Math.PI * highCutoff / SAMPLE_RATE);
        lowpassPrev = lowpassPrev + alpha_low * (sample - lowpassPrev);
        sample = lowpassPrev;
        
        return sample;
    }
    
    private float colorizeNoise(float noiseSample) {
        // High-pass для шума
        float aHigh = (float)Math.exp(-2.0 * Math.PI * NOISE_HP_CUTOFF / SAMPLE_RATE);
        float hpOut = aHigh * (noiseHpPrev + noiseSample - noiseHpInput);
        noiseHpInput = noiseSample;
        noiseHpPrev = hpOut;
        // Low-pass для шума
        float aLow = (float)Math.exp(-2.0 * Math.PI * NOISE_LP_CUTOFF / SAMPLE_RATE);
        noiseLpPrev = noiseLpPrev + aLow * (hpOut - noiseLpPrev);
        return noiseLpPrev;
    }

    private float applyDistortion(float sample, float driveDB) {
        float drive = (float)Math.pow(10, driveDB / 20.0);
        sample *= drive;
        
        // Жесткий distortion
        sample = (float)Math.tanh(sample * 1.2);
        
        return sample * 0.90f; // Компенсация громкости
    }
    
    private float applyCompressor(float sample, float ratio) {
        float abs = Math.abs(sample);
        if (abs > COMP_THRESHOLD) {
            float excess = abs - COMP_THRESHOLD;
            float compressed = COMP_THRESHOLD + excess / ratio;
            sample = sample * (compressed / abs);
        }
        return sample;
    }
    
    // --- End beep helpers ---
    private void startEndBeep() {
        beepTotalSamples = Math.max(1, (int)(BEEP_MS * SAMPLE_RATE / 1000f));
        beepSamplesRemaining = beepTotalSamples;
        beepPhase = 0f;
    }

    private float nextBeepSample() {
        // Временная огибающая: короткая атака и спад (остаток линейно до нуля)
        int attack = Math.max(1, (int)(BEEP_ATTACK_MS * SAMPLE_RATE / 1000f));
        int decay = Math.max(1, (int)(BEEP_DECAY_MS * SAMPLE_RATE / 1000f));
        int idx = beepTotalSamples - beepSamplesRemaining;
        float env;
        if (idx < attack) {
            env = idx / (float)attack;
        } else if (idx < attack + decay) {
            env = 1f - (idx - attack) / (float)decay;
            env = Math.max(0f, env);
        } else {
            // быстрый релиз до нуля
            float remain = Math.max(0, beepTotalSamples - (attack + decay));
            float t = idx - (attack + decay);
            env = Math.max(0f, 1f - (t / (remain + 1e-6f)));
        }
        // Синус
        float sample = (float)Math.sin(2 * Math.PI * beepPhase) * env * BEEP_LEVEL;
        beepPhase += BEEP_FREQ_HZ / SAMPLE_RATE;
        if (beepPhase >= 1f) beepPhase -= 1f;
        beepSamplesRemaining--;
        return sample;
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    
    public void reset() {
        isTransmitting = false;
        silenceCounter = 0;
        lowpassPrev = 0f;
        highpassPrev = 0f;
        highpassInput = 0f;
        env = 0f;
        smoothedDistance = 0f;
        distanceInited = false;
        noiseHpPrev = noiseHpInput = noiseLpPrev = 0f;
        beepSamplesRemaining = 0;
        beepTotalSamples = 0;
        beepPhase = 0f;
    }
    
    public boolean isTransmitting() {
        return isTransmitting;
    }
}