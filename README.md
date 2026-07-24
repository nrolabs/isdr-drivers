# iSDR Drivers

Hardware driver host for the **iSDR** app. This APK contains the GPL radio
drivers — [librtlsdrk](https://github.com/nrolabs/librtlsdrk),
[libhackrfk](https://github.com/nrolabs/libhackrfk),
[libhl2sdrk](https://github.com/nrolabs/libhl2sdrk) and
[libg2sdrk](https://github.com/nrolabs/libg2sdrk) — behind a small foreground
service (`DriverService`) that the iSDR app drives over a loopback TCP socket.

The host is deliberately dumb: it moves raw IQ samples and hardware control
commands only. All signal processing (demodulation, filtering, digital modes,
CW decoding) lives in the client application.

## Protocol

The wire contract lives in the [`proto`](proto/) submodule
([isdr-proto](https://github.com/nrolabs/isdr-proto), dual-licensed): one
frame per command/event, `[u8 opcode][i32 length][payload]`, big-endian, over
`127.0.0.1:45733`. Commands map 1:1 onto driver client calls; events carry IQ
blocks + display spectrum, connection status, telemetry and sweep blocks.

## Build

```
git submodule update --init --recursive
./gradlew assembleRelease
```

## License

GPLv2 or later — see [LICENSE](LICENSE). The bundled drivers keep their own
copyright notices (see each driver's `COPYING.md`).
