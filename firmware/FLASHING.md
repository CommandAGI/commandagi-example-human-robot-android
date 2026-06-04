# Flashing the ESP32 drone-link relay

In **drone mode**, the app sends control packets over USB to an ESP32 running this relay firmware.
The ESP32 joins the drone's own Wi-Fi access point and forwards the packets as UDP — so your phone
keeps its normal network while still flying the drone.

```
[ phone ] --USB-C--> [ ESP32 ] --Wi-Fi--> [ drone AP ]
```

## What you need

- An **ESP32-S3** or **ESP32-C3 / C6** dev board with native USB (e.g. *ESP32-S3-DevKitC-1* or
  *Seeed XIAO ESP32C6*). Any board whose USB-UART bridge is CP210x / CH34x / FTDI also works.
- A USB-C cable, plus a USB-C **OTG** adapter if your board has a different connector.
- A computer with [PlatformIO](https://platformio.org/install) (`pip install platformio`).
- A cheap AP-mode camera drone in the `WIFI_8K-*` / E99 family (the protocol the relay speaks).

## Flash it

```bash
cd firmware/esp32_drone_link
pio run                                   # build
pio run -t upload --upload-port /dev/ttyACM0   # flash (adjust the port)
```

The default PlatformIO environment targets the Seeed XIAO ESP32C6. For an ESP32-S3 DevKitC:

```bash
pio run -e esp32s3 -t upload --upload-port /dev/ttyACM0
```

## Verify

After flashing, the firmware prints `BOOT` over serial at 921600 baud and answers Wi-Fi scan and
config frames. The app does the rest:

1. Plug the ESP32 into the phone. **Settings → What am I driving? → Drone** shows it as *detected*.
2. The **Wi-Fi networks** section opens — power on the drone, **Scan**, and tap its AP
   (`WIFI_8K-…`).
3. On a successful join the **Drone status & test control** section opens — use the test buttons
   (Takeoff / Forward / Yaw / …) to confirm the link before handing control to an agent.

## Protocol

The serial link is the `DL`-framed protocol shared with the
[drone-control](https://github.com/) project: an 8-byte header
(`"DL"`, version, type, seq, payload length) + payload + CRC-16/CCITT. Message types: `CONFIG`
(join an AP), `SEND` (forward a UDP control packet), `SCAN`; replies `STATUS` / `ACK` / `ERROR`.
The drone packets themselves are the 9-byte WIFI_8K command `03 66 R P T Y FLAGS XOR 99`. See
[`app/src/main/java/com/commandagi/humanrobot/drone/`](../app/src/main/java/com/commandagi/humanrobot/drone/)
for the Android implementation and `esp32_drone_link/src/main.cpp` for the firmware side.

> Flashing **from the phone** (USB DFU) isn't wired up yet — flash from a computer once, then the
> board stays as your relay. Contributions welcome.
