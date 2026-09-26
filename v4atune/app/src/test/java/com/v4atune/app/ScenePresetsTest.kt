package com.v4atune.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePresetsTest {
    @Test
    fun allPresetScenesUseSupportedEqAndFirSizes() {
        Scene.entries.filter { it != Scene.Custom }.forEach { scene ->
            val options = ScenePresets.apply(scene)
            assertTrue(options.eqBands in listOf(10, 15, 25, 31))
            assertTrue(options.firTaps in listOf(1024, 2048, 4096, 8192))
            assertEquals(Component.entries.toSet(), options.policies.keys)
        }
    }

    @Test
    fun gamePresetPrioritizesLowLatency() {
        val options = ScenePresets.apply(Scene.Game)
        assertEquals(TestMode.Quick, options.mode)
        assertEquals(31, options.eqBands)
        assertEquals(1024, options.firTaps)
        assertEquals(Policy.Off, options.policies[Component.Convolver])
        assertEquals(Policy.Off, options.policies[Component.Reverb])
        assertEquals(Policy.Off, options.policies[Component.DiffSurround])
        assertEquals(Policy.On, options.policies[Component.StereoImager])
    }

    @Test
    fun outdoorPresetEnablesControlledLoudnessChain() {
        val options = ScenePresets.apply(Scene.Outdoor)
        assertEquals(Target.Loudness, options.target)
        assertEquals(Policy.On, options.policies[Component.PlaybackGain])
        assertEquals(Policy.On, options.policies[Component.Lufs])
        assertEquals(Policy.On, options.policies[Component.MultibandCompressor])
        assertEquals(Policy.On, options.policies[Component.PsychoBass])
    }

    @Test
    fun nightPresetSuppressesBassAndUsesLightDynamics() {
        val options = ScenePresets.apply(Scene.Night)
        assertEquals(Target.Vocal, options.target)
        assertEquals(Policy.On, options.policies[Component.Lufs])
        assertEquals(Policy.On, options.policies[Component.FetCompressor])
        assertEquals(Policy.Off, options.policies[Component.Bass])
        assertEquals(Policy.Off, options.policies[Component.PsychoBass])
        assertEquals(Policy.On, options.policies[Component.Clarity])
    }

    @Test
    fun viper31BandCentersMatchDriverLayout() {
        val expected = doubleArrayOf(
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0,
            125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
            800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0,
            4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
        )
        assertArrayEquals(expected, Acoustics.eq31, 0.0)
    }

    @Test
    fun customScenePreservesManualValues() {
        val manual = TuneOptions(
            scene = Scene.Music,
            target = Target.Bass,
            mode = TestMode.Deep,
            eqBands = 25,
            firTaps = 8192,
        )
        val custom = ScenePresets.apply(Scene.Custom, manual)
        assertEquals(Scene.Custom, custom.scene)
        assertEquals(Target.Bass, custom.target)
        assertEquals(TestMode.Deep, custom.mode)
        assertEquals(25, custom.eqBands)
        assertEquals(8192, custom.firTaps)
    }
}
