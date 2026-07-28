package com.isaklab.isdrdrivers.core

/**
 * Central defaults and shared constants for the SDR pipeline: frequency limits,
 * sample rates, demod bandwidth presets, FFT size and the audio output rate.
 *
 * Provides static configuration data to ensure consistency across the UI, 
 * the DSP thread, and hardware drivers without redundant initialization.
 */
object SDRConfig {

    const val MIN_FREQUENCY_HZ = 24_000_000L
    const val MAX_FREQUENCY_HZ = 1_766_000_000L

    // Hermes-Lite 2 tunes 0–38.4 MHz: the AD9866 first Nyquist zone at the
    // default 76.8 MHz sample clock. Beyond this the direct-sampling RX aliases.
    const val HL2_MIN_FREQUENCY_GHZ = 0.00001   // 10 kHz
    const val HL2_MAX_FREQUENCY_GHZ = 0.0384     // 38.4 MHz
    const val HL2_DEFAULT_FREQUENCY_GHZ = 0.0071 // 7.1 MHz (40 m)

    const val DEFAULT_FREQUENCY_GHZ = 0.1
    const val DEFAULT_SAMPLE_RATE_HZ = 2_048_000
    const val DEFAULT_DEMOD_BW_KHZ = 200.0
    const val DEFAULT_GAIN_DB = 25
    const val DEFAULT_DB_RANGE = 100
    const val DEFAULT_PPM = 0

    const val AUDIO_SAMPLE_RATE = 48000

    const val FFT_SIZE = 800

    val SAMPLE_RATES = listOf(
        240_000 to "240 kHz",
        300_000 to "300 kHz",
        960_000 to "960 kHz",
        1_024_000 to "1.024 MHz",
        1_152_000 to "1.152 MHz",
        1_200_000 to "1.2 MHz",
        1_440_000 to "1.44 MHz",
        1_600_000 to "1.6 MHz",
        1_800_000 to "1.8 MHz",
        2_048_000 to "2.048 MHz",
        2_400_000 to "2.4 MHz",
        2_560_000 to "2.56 MHz",
        2_880_000 to "2.88 MHz",
        3_200_000 to "3.2 MHz"
    )

    val DEMOD_BW_PRESETS = listOf(
        8_000 to "8 kHz",
        12_500 to "12.5 kHz",
        25_000 to "25 kHz",
        50_000 to "50 kHz",
        100_000 to "100 kHz",
        200_000 to "200 kHz"
    )

    enum class Modulation(val displayName: String) {
        FM("FM"),
        AM("AM"),
        USB("USB"),
        LSB("LSB")
    }

    enum class ConnectionMode {
        TCP,
        USB
    }
}
