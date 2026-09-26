package com.v4atune.app

import org.json.JSONArray
import org.json.JSONObject

object ProfileCodec {
    fun commands(root: JSONObject): List<ViperCommand> = buildList {
        fun bool(param: Int, group: String, key: String) =
            add(ViperCommand(param, WireValue.Bool(root.getJSONObject(group).getBoolean(key))))
        fun int(param: Int, group: String, key: String) =
            add(ViperCommand(param, WireValue.IntValue(root.getJSONObject(group).getInt(key))))
        fun float(param: Int, group: String, key: String) =
            add(ViperCommand(param, WireValue.FloatValue(root.getJSONObject(group).getDouble(key).toFloat())))
        fun indexedBool(param: Int, values: JSONArray) {
            for (i in 0 until values.length()) add(ViperCommand(param, WireValue.Bool(values.getBoolean(i), i)))
        }
        fun indexedInt(param: Int, values: JSONArray) {
            for (i in 0 until values.length()) add(ViperCommand(param, WireValue.IntValue(values.getInt(i), i)))
        }
        fun indexedFloat(param: Int, values: JSONArray) {
            for (i in 0 until values.length()) add(ViperCommand(param, WireValue.FloatValue(values.getDouble(i).toFloat(), i)))
        }

        float(ViperParams.MASTER_LIMITER_THRESHOLD, "masterLimiter", "threshold")
        float(ViperParams.MASTER_LIMITER_OUTPUT_VOLUME, "masterLimiter", "outputVolume")
        float(ViperParams.MASTER_LIMITER_CHANNEL_PAN, "masterLimiter", "channelPan")

        bool(ViperParams.PLAYBACK_GAIN_ENABLE, "playbackGainControl", "enable")
        float(ViperParams.PLAYBACK_GAIN_STRENGTH, "playbackGainControl", "strength")
        float(ViperParams.PLAYBACK_GAIN_MAX, "playbackGainControl", "maxGain")
        float(ViperParams.PLAYBACK_GAIN_THRESHOLD, "playbackGainControl", "outputThreshold")

        bool(ViperParams.LUFS_ENABLE, "lufs", "enable")
        float(ViperParams.LUFS_TARGET, "lufs", "target")
        float(ViperParams.LUFS_MAX_GAIN, "lufs", "maxGain")
        int(ViperParams.LUFS_SPEED, "lufs", "speed")

        val fet = root.getJSONObject("fetCompressor")
        listOf(
            ViperParams.FET_ENABLE to WireValue.Bool(fet.getBoolean("enable")),
            ViperParams.FET_THRESHOLD to WireValue.FloatValue(fet.getDouble("threshold").toFloat()),
            ViperParams.FET_RATIO to WireValue.FloatValue(fet.getDouble("ratio").toFloat()),
            ViperParams.FET_KNEE to WireValue.FloatValue(fet.getDouble("knee").toFloat()),
            ViperParams.FET_KNEE_AUTO to WireValue.Bool(fet.getBoolean("kneeAuto")),
            ViperParams.FET_GAIN to WireValue.FloatValue(fet.getDouble("gain").toFloat()),
            ViperParams.FET_GAIN_AUTO to WireValue.Bool(fet.getBoolean("gainAuto")),
            ViperParams.FET_ATTACK to WireValue.FloatValue(fet.getDouble("attack").toFloat()),
            ViperParams.FET_ATTACK_AUTO to WireValue.Bool(fet.getBoolean("attackAuto")),
            ViperParams.FET_RELEASE to WireValue.FloatValue(fet.getDouble("release").toFloat()),
            ViperParams.FET_RELEASE_AUTO to WireValue.Bool(fet.getBoolean("releaseAuto")),
            ViperParams.FET_KNEE_MULTI to WireValue.FloatValue(fet.getDouble("kneeMulti").toFloat()),
            ViperParams.FET_MAX_ATTACK to WireValue.FloatValue(fet.getDouble("maxAttack").toFloat()),
            ViperParams.FET_MAX_RELEASE to WireValue.FloatValue(fet.getDouble("maxRelease").toFloat()),
            ViperParams.FET_CREST to WireValue.FloatValue(fet.getDouble("crest").toFloat()),
            ViperParams.FET_ADAPT to WireValue.FloatValue(fet.getDouble("adapt").toFloat()),
            ViperParams.FET_NO_CLIP to WireValue.Bool(fet.getBoolean("noClip")),
        ).forEach { add(ViperCommand(it.first, it.second)) }

        bool(ViperParams.BASS_ENABLE, "bass", "enable")
        int(ViperParams.BASS_MODE, "bass", "mode")
        int(ViperParams.BASS_FREQUENCY, "bass", "frequency")
        float(ViperParams.BASS_GAIN, "bass", "gain")
        bool(ViperParams.BASS_ANTI_POP, "bass", "antiPop")

        bool(ViperParams.BASS_MONO_ENABLE, "bassMono", "enable")
        int(ViperParams.BASS_MONO_MODE, "bassMono", "mode")
        int(ViperParams.BASS_MONO_FREQUENCY, "bassMono", "frequency")
        float(ViperParams.BASS_MONO_GAIN, "bassMono", "gain")
        bool(ViperParams.BASS_MONO_ANTI_POP, "bassMono", "antiPop")

        bool(ViperParams.PSYCHO_BASS_ENABLE, "psychoacousticBass", "enable")
        int(ViperParams.PSYCHO_BASS_CUTOFF, "psychoacousticBass", "cutoff")
        float(ViperParams.PSYCHO_BASS_INTENSITY, "psychoacousticBass", "intensity")
        int(ViperParams.PSYCHO_BASS_HARMONIC_ORDER, "psychoacousticBass", "harmonicOrder")
        float(ViperParams.PSYCHO_BASS_ORIGINAL_LEVEL, "psychoacousticBass", "originalLevel")

        bool(ViperParams.SPECTRUM_ENABLE, "spectrumExtension", "enable")
        int(ViperParams.SPECTRUM_STRENGTH, "spectrumExtension", "strength")
        float(ViperParams.SPECTRUM_EXCITER, "spectrumExtension", "exciter")

        bool(ViperParams.EQ_ENABLE, "equalizer", "enable")
        int(ViperParams.EQ_BAND_COUNT, "equalizer", "bandCount")
        val eq = root.getJSONObject("equalizer").getJSONArray("bands")
        add(
            ViperCommand(
                ViperParams.EQ_BAND_LEVELS,
                WireValue.Floats(FloatArray(eq.length()) { i -> eq.getDouble(i).toFloat() }),
            ),
        )

        bool(ViperParams.CONVOLVER_ENABLE, "convolver", "enable")
        float(ViperParams.CONVOLVER_CROSS, "convolver", "crossChannel")
        bool(ViperParams.DDC_ENABLE, "ddc", "enable")

        bool(ViperParams.FIELD_ENABLE, "fieldSurround", "enable")
        float(ViperParams.FIELD_WIDENING, "fieldSurround", "widening")
        float(ViperParams.FIELD_MID_IMAGE, "fieldSurround", "midImage")
        int(ViperParams.FIELD_DEPTH, "fieldSurround", "depth")

        bool(ViperParams.DIFF_ENABLE, "diffSurround", "enable")
        float(ViperParams.DIFF_DELAY, "diffSurround", "delay")
        bool(ViperParams.DIFF_REVERSE, "diffSurround", "reverse")
        float(ViperParams.DIFF_MIX, "diffSurround", "wetDryMix")
        int(ViperParams.DIFF_LP, "diffSurround", "lpCutoff")

        bool(ViperParams.IMAGER_ENABLE, "stereoImager", "enable")
        float(ViperParams.IMAGER_LOW, "stereoImager", "lowWidth")
        float(ViperParams.IMAGER_MID, "stereoImager", "midWidth")
        float(ViperParams.IMAGER_HIGH, "stereoImager", "highWidth")
        int(ViperParams.IMAGER_LOW_X, "stereoImager", "lowCrossover")
        int(ViperParams.IMAGER_HIGH_X, "stereoImager", "highCrossover")

        bool(ViperParams.HEADPHONE_SURROUND_ENABLE, "headphoneSurround", "enable")
        int(ViperParams.HEADPHONE_SURROUND_QUALITY, "headphoneSurround", "quality")

        bool(ViperParams.REVERB_ENABLE, "reverb", "enable")
        float(ViperParams.REVERB_ROOM, "reverb", "roomSize")
        float(ViperParams.REVERB_WIDTH, "reverb", "width")
        float(ViperParams.REVERB_DAMP, "reverb", "damp")
        float(ViperParams.REVERB_WET, "reverb", "wet")
        float(ViperParams.REVERB_DRY, "reverb", "dry")

        bool(ViperParams.DYNAMIC_SYSTEM_ENABLE, "dynamicSystem", "enable")
        int(ViperParams.DYNAMIC_SYSTEM_X_LOW, "dynamicSystem", "xLow")
        int(ViperParams.DYNAMIC_SYSTEM_X_HIGH, "dynamicSystem", "xHigh")
        int(ViperParams.DYNAMIC_SYSTEM_Y_LOW, "dynamicSystem", "yLow")
        int(ViperParams.DYNAMIC_SYSTEM_Y_HIGH, "dynamicSystem", "yHigh")
        float(ViperParams.DYNAMIC_SYSTEM_SIDE_LOW, "dynamicSystem", "sideGainLow")
        float(ViperParams.DYNAMIC_SYSTEM_SIDE_HIGH, "dynamicSystem", "sideGainHigh")
        float(ViperParams.DYNAMIC_SYSTEM_STRENGTH, "dynamicSystem", "strength")

        bool(ViperParams.CLARITY_ENABLE, "clarity", "enable")
        int(ViperParams.CLARITY_MODE, "clarity", "mode")
        float(ViperParams.CLARITY_GAIN, "clarity", "gain")
        bool(ViperParams.CURE_ENABLE, "cure", "enable")
        int(ViperParams.CURE_PRESET, "cure", "crossfeedPreset")
        bool(ViperParams.TUBE_ENABLE, "tubeSimulator", "enable")
        bool(ViperParams.ANALOG_ENABLE, "analogX", "enable")
        int(ViperParams.ANALOG_MODE, "analogX", "mode")
        bool(ViperParams.SPEAKER_CORRECTION_ENABLE, "speakerCorrection", "enable")

        val mbc = root.getJSONObject("multibandCompressor")
        add(ViperCommand(ViperParams.MBC_ENABLE, WireValue.Bool(mbc.getBoolean("enable"))))
        add(ViperCommand(ViperParams.MBC_BAND_COUNT, WireValue.IntValue(5)))
        indexedBool(ViperParams.MBC_BAND_ENABLE, mbc.getJSONArray("bandEnables"))
        indexedInt(ViperParams.MBC_CROSSOVER, mbc.getJSONArray("crossovers"))
        indexedFloat(ViperParams.MBC_THRESHOLD, mbc.getJSONArray("thresholds"))
        indexedFloat(ViperParams.MBC_RATIO, mbc.getJSONArray("ratios"))
        indexedFloat(ViperParams.MBC_KNEE, mbc.getJSONArray("knees"))
        indexedBool(ViperParams.MBC_KNEE_AUTO, mbc.getJSONArray("kneeAutos"))
        indexedFloat(ViperParams.MBC_GAIN, mbc.getJSONArray("gains"))
        indexedBool(ViperParams.MBC_GAIN_AUTO, mbc.getJSONArray("gainAutos"))
        indexedFloat(ViperParams.MBC_ATTACK, mbc.getJSONArray("attacks"))
        indexedBool(ViperParams.MBC_ATTACK_AUTO, mbc.getJSONArray("attackAutos"))
        indexedFloat(ViperParams.MBC_RELEASE, mbc.getJSONArray("releases"))
        indexedBool(ViperParams.MBC_RELEASE_AUTO, mbc.getJSONArray("releaseAutos"))
        indexedFloat(ViperParams.MBC_KNEE_MULTI, mbc.getJSONArray("kneeMultis"))
        indexedFloat(ViperParams.MBC_MAX_ATTACK, mbc.getJSONArray("maxAttacks"))
        indexedFloat(ViperParams.MBC_MAX_RELEASE, mbc.getJSONArray("maxReleases"))
        indexedFloat(ViperParams.MBC_CREST, mbc.getJSONArray("crests"))
        indexedFloat(ViperParams.MBC_ADAPT, mbc.getJSONArray("adapts"))
        indexedBool(ViperParams.MBC_NO_CLIP, mbc.getJSONArray("noClips"))

        val dyn = root.getJSONObject("dynamicEq")
        add(ViperCommand(ViperParams.DYN_EQ_ENABLE, WireValue.Bool(dyn.getBoolean("enable"))))
        add(ViperCommand(ViperParams.DYN_EQ_BAND_COUNT, WireValue.IntValue(dyn.getInt("bandCount"))))
        indexedInt(ViperParams.DYN_EQ_FREQUENCY, dyn.getJSONArray("freqs"))
        indexedFloat(ViperParams.DYN_EQ_Q, dyn.getJSONArray("qs"))
        indexedFloat(ViperParams.DYN_EQ_GAIN, dyn.getJSONArray("gains"))
        indexedFloat(ViperParams.DYN_EQ_THRESHOLD, dyn.getJSONArray("thresholds"))
        indexedFloat(ViperParams.DYN_EQ_ATTACK, dyn.getJSONArray("attacks"))
        indexedFloat(ViperParams.DYN_EQ_RELEASE, dyn.getJSONArray("releases"))
        indexedInt(ViperParams.DYN_EQ_FILTER_TYPE, dyn.getJSONArray("filterTypes"))
    }

    fun neutralCommands(): List<ViperCommand> = listOf(
        ViperCommand(ViperParams.PLAYBACK_GAIN_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.LUFS_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.FET_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.MBC_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.DDC_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.SPECTRUM_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.EQ_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.DYN_EQ_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.CONVOLVER_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.FIELD_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.DIFF_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.IMAGER_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.HEADPHONE_SURROUND_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.REVERB_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.DYNAMIC_SYSTEM_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.PSYCHO_BASS_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.BASS_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.BASS_MONO_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.CLARITY_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.CURE_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.TUBE_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.ANALOG_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.SPEAKER_CORRECTION_ENABLE, WireValue.Bool(false)),
        ViperCommand(ViperParams.MASTER_LIMITER_OUTPUT_VOLUME, WireValue.FloatValue(1.0f)),
        ViperCommand(ViperParams.MASTER_LIMITER_THRESHOLD, WireValue.FloatValue(1.0f)),
        ViperCommand(ViperParams.MASTER_LIMITER_CHANNEL_PAN, WireValue.FloatValue(0.0f)),
    )
}
