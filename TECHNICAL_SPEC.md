# iSDR Drivers (`isdr-drivers`) — Technical Specification

High-level contracts and invariants. Implementation history belongs in the git
log, not here.

## 1. Scope and boundary

`isdr-drivers` is a standalone GPL Android app: a launcher activity
(`DriverActivity`, which also handles `USB_DEVICE_ATTACHED`) plus an exported
foreground service (`DriverService`, `connectedDevice`) that serves the
`isdr-proto` wire contract over a socket. One accepted connection becomes one
`DriverSession`.

Its job is hardware access and `isdr-proto` serialization. **No demodulation
chain lives here** — no channelizer, decimator or demodulator; that is
`isdr-app`/`isdr-station` work. What does run locally is the minimum the wire
contract needs: the spectrum path (`FFTProcessor`, `SpectrumWorker`) and the TX
interpolators that lift 48 kHz modulator output to the radio's sample rate.
Drivers must not depend on app resources or classes.

## 2. Session ownership (invariant)

Per-generation connection state — socket, threads, sequence trackers,
accumulators — belongs in a single session object published atomically, and
teardown operates **only on the session instance it captured**. `Hl2Client`
is the reference implementation: a private `Session` class behind an
`AtomicReference`, with `disconnect` comparing identity before clearing
(`sessionRef.compareAndSet(s, null)`), so a late teardown can never dismantle a
newer connection.

This is the target pattern for every client. `G2Client`, `HackRfClient`,
`RTLUSBClient` and `RTLTCPClient` still keep flat `@Volatile` fields; converting
them is open work, not an alternative design.

## 3. Thread failure is never silent (invariant)

Radio loops are raw daemon threads created by the `DspThread` factory at
`THREAD_PRIORITY_URGENT_AUDIO` (delivery threads at `THREAD_PRIORITY_AUDIO`).
The factory wraps each body in a catch-all that logs the throwable **with its
stack** and invokes an `onFailure` callback, which clients wire to a real link
teardown (`failLink()` / `failStream()`).

The invariant: a dead thread must never leave the session reporting
"connected". No loop may swallow its exception and exit quietly. Inside these
loops, allocation is a bug — every per-block allocation is a GC pause and an
underrun.

## 4. Payload limits

A session accepts at most **4 KiB** per frame before authentication and
**1 MiB** after it. The largest legitimate inbound frame is `CMD_TX_IQ` at
~8 KiB, so the cap is pure abuse protection, not a working limit.

## 5. Per-radio notes

* **RTL-SDR** — tuner I²C and register access are serialized on a single pinned
  worker (`rtlsdr-usb` executor); commands are FIFO-queued through
  `sendCommand`. Bulk IQ transfer runs on its own threads, so control transfers
  proceed without interrupting the stream. `RTLUSBClient` instances are
  single-use: after `disconnect()` the executor is shut down, so reconnecting
  means a new instance.
* **Hermes-Lite 2** — Ethernet/UDP. The wire format is the Android-free
  `Hl2Protocol` codec (fixed offsets and C0 register banks), JVM-tested.
  `Hl2Client` owns transport, RX accumulation and TX streaming.
  **IO board:** the control register is reset exactly once per session, and
  teardown writes `REG_RF_INPUTS = 0` while the socket is still open, flushing
  control frames until it lands — the J9 routing pins are sticky in firmware and
  stay latched otherwise.
* **ANAN-G2** — the stock Protocol-2 port map: separate UDP ports for general
  control (1024), high priority / run + PTT (1027), TX IQ (1029) and the DDC RX
  streams, which are keyed on the radio's **source** port (1035..1041). PTT
  rides the high-priority port; TX IQ is wall-clock paced on its own thread, so
  keying never queues behind sample data.

## 6. Sequence tracking

`SeqTracker` (in `core/`) watches the 32-bit packet sequence numbers on HL2 EP6
and each G2 receiver. A discontinuity is counted as a gap event and surfaces to
the host as `RadioTelemetry.rxGaps` in `EV_TELEMETRY`.
