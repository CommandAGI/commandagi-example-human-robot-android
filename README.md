# Be a Robot — CommandAGI human-as-robot (Android)

An Android example that turns **you** into a robot on [CommandAGI](https://commandagi.com). The phone
registers itself as a robot: its **camera fills the screen and streams up** as the robot's
observation, and the **drive actions a remote driver sends** — move forward / back / turn left /
turn right / stop — appear **big at the bottom of the screen** for you to physically perform.

A person (in the CommandAGI web UI), an AI agent, or another developer's SDK client can then "drive
you" like any other robot. Under the hood it's the **producer side** of the robot API: the app is a
`RobotBridge` — it streams `frame` messages up and receives `control` messages down (see
[the robot developer API](https://commandagi.com/docs/robots)).

## Run it

1. Open this folder in **Android Studio** (it'll sync Gradle and generate the wrapper), or
   `./gradlew installDebug` once the wrapper is present.
2. Install on a phone and grant **camera** permission.
3. Tap **⚙ Settings** and paste a CommandAGI **API key** (operator scope — make one in your dashboard).
4. The app registers a robot and goes **live**. To drive yourself, open the session in the web app
   (or have an agent / the SDK send actions) and watch the instructions appear at the bottom — then
   move as told. The phone's camera is what the driver sees.

## Notes (this is a dev-speed example)

- **Auth:** API key pasted into Settings. A native "Sign in with CommandAGI" OAuth flow is a TODO —
  the API-key path is intentionally the fast route here.
- The YUV→JPEG frame conversion is the simple path (fine on most devices; tighten per-device pixel
  strides if colors look off).
- Build is done in Android Studio (no wrapper jar is committed; it's regenerated on first sync).

Built on the CommandAGI robot API. SDKs: [Python](https://github.com/commandAGI/commandagi-python) ·
[Node](https://github.com/commandAGI/commandagi-node). MIT licensed.
