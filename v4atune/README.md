# V4ATune

V4ATune is a rooted Android calibration companion for ViPER4Android RE AIDL.

## What “best” means in this project

The default **Reference / Hi-Fi** target optimizes in this order:

1. Smooth, stable frequency response in the useful smartphone-speaker band.
2. No clipping and enough DSP headroom.
3. Avoid unnecessary nonlinear processing.
4. Preserve transients and programme dynamics unless the user selects a loudness-oriented profile.
5. Use psychoacoustic bass rather than unsafe sub-bass boost when the micro-speaker cannot reproduce the requested low-frequency energy.
6. Use spatial processing only when the selected target asks for it.
7. Verify the result with a second speaker-to-microphone measurement pass and correct residual error.

This is intentionally not “turn every switch on”.

## Component policy

Every component is assigned one of three policies:

- **Auto** — enable only when the measurement and target justify it.
- **On / optimize** — enable and calculate actual parameters for that component.
- **Off** — keep it out of the signal chain.

For example, forcing **Convolver** on generates a device-specific FIR correction WAV and writes it to ViPER's Kernel directory. It does not merely set the enable bit.

The current speaker build exposes the complete ViPER effect chain, but ViPER DDC is marked as headphone/VDC-specific instead of pretending that an empty DDC profile is useful for the built-in speaker.

## Measurement

The app:

- requests microphone and root once;
- backs up ViPER Room DB and DataStore;
- writes a neutral baseline;
- uses native AudioTrack + AudioRecord (UNPROCESSED when supported);
- disables AGC / noise suppression / AEC when the platform permits;
- measures 25 bands from 63 Hz to 16 kHz;
- estimates fundamental level, SNR, peak level, and 2nd–4th harmonic THD;
- measures L/R 1 kHz output separately;
- builds a full ViPER profile;
- optionally creates a 1024-tap correction FIR;
- applies the full profile;
- performs a second verification pass and refines residual correction.

## Important limitation

A phone measuring its own speaker with its own microphone is a **relative self-calibration**, not an anechoic/reference-microphone measurement. The microphone response, close physical geometry, chassis coupling and room reflections are part of the measured transfer function. The planner therefore limits boost, avoids chasing low-frequency/null errors, and verifies the result after application.

A later reference-microphone mode can use an external USB/calibrated microphone at the listening position for substantially higher absolute accuracy.
