# iSDR Drivers Service (isdr-drivers) - Deep Technical Specification

## 1. Headless Hardware Isolation
`isdr-drivers` is a standalone Android Service. Its absolute prime directive is hardware interaction and serialization of `isdr-proto` data. **No DSP logic occurs in this module.**

## 2. Hardware Subsystem Intricacies
### 2.1 The RTL-SDR Subsystem (`RTLUSBClient.kt`)
The RTL-SDR communicates with the tuner chip via an internal I2C bus. If the main stream pulls IQ samples while a separate thread sends an I2C command, the hardware bus collides.
**Resolution**: `DriverSession` implements a single pinned worker thread. All UI commands are placed into a thread-safe FIFO queue and executed serially between USB bulk transfers.

### 2.2 Hermes-Lite 2 Subsystem (`Hl2Protocol.kt`)
The HL2 connects via Ethernet/UDP. Setting the transmit frequency requires manipulating precise byte offsets in the C0 payload bank.
**Network Jitter Tracking**: `SeqTracker` analyzes the 32-bit sequence numbers in the HL2 packets. If `seq_new != seq_old + 1`, the dropped frame is pushed into the `RadioTelemetry` `rxGaps` counter.

### 2.3 ANAN-G2 Subsystem (`G2Protocol.kt`)
When transmitting voice, latency is deadly. `G2Protocol` separates control frames from IQ payload frames. High-priority PTT commands bypass the deep IQ buffers and are pushed directly to the hardware's fast-path port.

## 3. Real-Time Thread Constraints (`DspThread.kt`)
The `DspThread` is instantiated as a raw `java.lang.Thread` utilizing `THREAD_PRIORITY_URGENT_AUDIO`. Inside this thread's `while(true)` loop, absolutely zero object instantiation (`new Object()`) is allowed. Any allocation guarantees a GC pause and a buffer underrun.
