package com.v4atune.app;

import java.util.EnumMap;
import java.util.Map;

final class TuneConfig {
    enum Target {
        REFERENCE("参考 / 高保真"),
        BALANCED("均衡悦耳"),
        VOCAL("人声清晰"),
        BASS("低频冲击"),
        SPATIAL("空间感"),
        LOUDNESS("户外 / 高响度");

        final String label;
        Target(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum Policy {
        AUTO("自动"),
        ON("开启并优化"),
        OFF("关闭");

        final String label;
        Policy(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum Effect {
        PLAYBACK_GAIN("回放增益控制"),
        LUFS("响度均衡"),
        FET_COMP("FET 压缩器"),
        MULTIBAND_COMP("多段压缩器"),
        DDC("ViPER DDC"),
        SPECTRUM("频谱扩展"),
        EQ("FIR / 图形均衡"),
        DYNAMIC_EQ("动态均衡器"),
        CONVOLVER("卷积器（FIR）"),
        FIELD_SURROUND("场环绕"),
        DIFF_SURROUND("差分环绕"),
        STEREO_IMAGER("立体声成像"),
        HEADPHONE_SURROUND("耳机环绕"),
        REVERB("混响"),
        DYNAMIC_SYSTEM("动态系统"),
        PSYCHO_BASS("心理声学低频"),
        BASS("ViPER 低音"),
        BASS_MONO("单声道低音"),
        CLARITY("ViPER 清晰度"),
        CURE("Cure / 交叉馈送"),
        TUBE("电子管模拟"),
        ANALOGX("AnalogX"),
        SPEAKER_CORRECTION("扬声器优化");

        final String label;
        Effect(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    Target target = Target.REFERENCE;
    boolean twoPass = true;
    boolean useInternalMic = true;
    final EnumMap<Effect, Policy> policies = new EnumMap<>(Effect.class);

    TuneConfig() {
        for (Effect e : Effect.values()) policies.put(e, Policy.AUTO);
    }

    Policy policy(Effect effect) {
        return policies.getOrDefault(effect, Policy.AUTO);
    }

    Map<Effect, Policy> snapshot() {
        return new EnumMap<>(policies);
    }
}
