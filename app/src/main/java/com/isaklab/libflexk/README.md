# libflexk

Kotlin driver for **FlexRadio** radios — the FLEX-6000 series
(6300/6400/6600/6700), the FLEX-8000 series and Aurora all speak the same
**SmartSDR network API** — TCP text command plane plus VITA-49
UDP data plane. Pure Kotlin — no NDK, no native libraries. Built for Android
but the wire codec is Android-free and JVM-testable.

Maintained by Isak — **PU3IAR**. Brought to you by [id.qsl.br](https://id.qsl.br),
a platform with tools for amateur radio operators.
YouTube: [@qraisak](https://www.youtube.com/@qraisak)

## Features

- **`FlexProtocol`** — the SmartSDR wire codec, free of any Android
  dependency and unit-tested on the JVM:
  - **VITA-49 preamble** parse (big-endian: packet type, class id with the
    FlexRadio OUI `0x1C2D`, TSI/TSF timestamps, trailer) and payload
    windowing.
  - **Discovery** decode: the radio broadcasts VITA packets on UDP 4992
    with an ASCII `key=value` payload (ip, port, model, serial, status).
  - **Command plane** formatting (`C<seq>|command`) and line dispatch for
    `R` replies, `S` status, `H` handle, `V` version, `M` messages.
  - **DAX-IQ** decode: interleaved float32 BE pairs at the reference
    `1/2^15` scale, class codes for the 24/48/96/192 kHz WIDE rates.
- **`FlexClient`** — network transport and session lifecycle:
  - LAN **discovery** listener, TCP command session with sequenced
    reply handlers, VITA UDP socket announced via `client udpport`.
  - RX chain: `display panafall create` → `slice create` → `stream create
    type=dax_iq`, IQ delivered as `FloatArray` (`i0,q0,…` in `[-1,1]`)
    with an optional power spectrum — the same callback contract as the
    other iSDR clients.
  - **PTT** (`xmit`), TX drive (`transmit set rfpower`), slice mode and
    tuning, DAX-IQ sample-rate selection.

## Reference

Ported faithfully from the published **FlexLib API v4.2.20**
(Copyright © FlexRadio Systems):
<https://www.flexradio.com/software/smartsdr-v4-2-20/>

## License

Dual-licensed: GPLv2+ only as distributed within the iSDR Drivers
application; all other uses require a separate license from the copyright
holder. See [LICENSE](LICENSE) and [COPYING.md](COPYING.md).
