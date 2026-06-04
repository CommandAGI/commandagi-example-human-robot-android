# Personal Driver — CommandAGI human/drone robot client (Android)

An Android example that lets a CommandAGI agent **drive something through your phone**. The phone
registers as a robot: its **camera fills the screen and streams up** as the robot's observation, and
the **drive actions a remote driver sends** — move forward / back / turn / stop — are carried out.

A **"What am I driving?"** setting (a segmented toggle, mirroring a squircle-vs-rounded-rect
preference) chooses *what* those actions control:

- **Human** — you are the robot. The instruction appears **big at the bottom** for you to perform,
  and can be **spoken aloud** and **vibrate** on each new direction.
- **Drone** — an **ESP32** plugged into the phone relays the actions to a Wi-Fi access-point camera
  drone (the `WIFI_8K-*` / E99 family). The phone camera still streams up as the observation.

Either way it's the **producer side** of the robot API: the app streams `frame` messages up and
receives `control` messages down (see [the robot developer API](https://commandagi.com/docs/robots)).

## Run it

1. Open this folder in **Android Studio** (it syncs Gradle and generates the wrapper), or
   `./gradlew installDebug` once the wrapper is present.
2. Install on a phone and grant **camera** permission.
3. Tap **⚙ Settings**, paste a CommandAGI **API key** (operator scope, from your dashboard), and pick
   **Human** or **Drone**.
4. The app registers a robot and goes **live**. Drive it from the web session, an agent, or the SDK.

## Human mode

The instruction (`MOVE FORWARD`, `TURN LEFT`, …) is shown large at the bottom; the camera is what the
driver sees. Options in Settings:

- **Dictate directions aloud** — text-to-speech speaks each instruction (hands-free / eyes-up).
- **Keep the screen awake** — hold the display on between instructions.
- **Vibrate on a new instruction** — a short haptic when the direction changes.
- **Camera** — back (what you see ahead) or front (selfie).

## Drone mode

Plug an **ESP32** running the relay firmware into the phone. The ESP32 joins the drone's own Wi-Fi AP
and forwards control packets over UDP, so the phone keeps its normal network.

```
[ phone ] --USB-C--> [ ESP32 ] --Wi-Fi--> [ drone AP ]
```

Settings → Drone shows a labeled wiring **diagram** with live ESP32-detected status, then three
sections that drive the connect flow:

1. **Wi-Fi networks visible to the ESP32** — opens once an ESP32 is detected. Power on the drone,
   **Scan**, and tap its AP (drone-looking SSIDs are flagged and sorted first).
2. **Drone status & test control** — opens automatically once a join passes the protocol check.
   Test buttons (Takeoff / Land / Forward / Back / Yaw / Up / Down / E-Stop) verify the link before
   you hand control to an agent.
3. **About the ESP32 relay** — what it is, how to get one, how to flash it.

The firmware and full instructions live in [`firmware/`](firmware/FLASHING.md). The Android side of
the wire protocol (`DL` serial framing + the WIFI_8K drone packet) is in
[`app/.../drone/`](app/src/main/java/com/commandagi/humanrobot/drone/). This is adapted from the
[drone-control](https://github.com/) ESP32-bridge architecture so the same control loop can mix link
types — a starting point; robot arms and other relays can follow the same pattern.

## Notes (this is a dev-speed example)

- **Auth:** API key in Settings. A native "Sign in with CommandAGI" OAuth flow is a TODO.
- **Flashing from the phone** (USB DFU) isn't wired up — flash the ESP32 once from a computer.
- Drone mode currently streams the **phone** camera as the observation; decoding the drone's own
  RTSP/RTP camera onboard is a follow-up.
- The YUV→JPEG frame conversion is the simple path; tighten per-device pixel strides if colors look
  off. No wrapper jar is committed; Android Studio regenerates it on first sync.

Built on the CommandAGI robot API. SDKs: [Python](https://github.com/commandAGI/commandagi-python) ·
[Node](https://github.com/commandAGI/commandagi-node). MIT licensed.
