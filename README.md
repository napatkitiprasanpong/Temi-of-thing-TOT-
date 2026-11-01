# temi-of-thinh (TOT)

An Android app (Kotlin, Jetpack Compose, Material 3) for testing and making use of a **temi robot**'s camera and tablet. It has two screens, chosen in the sidebar: **Camera & Depth** (live camera, depth map, gesture control) and **Hand Reading** (ASL fingerspelling that temi speaks aloud).

It was split out of the former *IAQ & IEQ Monitoring* app (its **Camera & Depth** and **Sign Language** tabs) and is now its own project. It has no connection to the air-quality app (IAQ-IEQ).

## Features

|Area|What it does|
|-|-|
|**Camera & Depth**|Live RGB view of the tablet camera next to a colour-coded depth map, with Left / Ahead / Right distance read-outs.|
|**Raise hand → follow me**|Pose model sees a raised hand; temi says hello and starts its follow mode.|
|**Walk to me**|temi walks to the person using only the tablet camera, then hands over to follow mode.|
|**Find person**|temi tilts its tablet up and down until the camera sees a person.|
|**T-pose = stop**|Both arms out to the sides stops following or walking.|
|**Hand Reading**|Reads ASL fingerspelling from the camera and has temi say the spelled word.|

## Requirements

* temi robot (V2: Android 6, or V3: Android 11), or any Android tablet or phone with the **Simulated robot** switch on
* Android Studio (recent version), JDK 17+, Android SDK 36
* App: `minSdk 23`, `targetSdk 36`, application id `com.leslie.tot`
* Dependencies (`app/build.gradle.kts`): `com.robotemi:sdk:1.138.0`, ONNX Runtime `1.17.3` (MoveNet pose model), TensorFlow Lite `2.16.1` (Hand Reading), CameraX `1.4.2`

## Build and install

1. Open the project in Android Studio and let Gradle sync.
2. Connect to temi with `adb connect <temi-ip>` (enable developer options on temi), or use the temi-recommended install method.
3. Run the `app` configuration. The app appears in temi's app list as **temi-of-thinh (TOT)**.
4. Allow the camera when asked.

## Camera & Depth view

The main screen shows what temi is looking at.

* **RGB Camera (left):** live preview of the tablet's camera (front camera, or the back camera if there is no front one). Android asks for camera permission the first time the app opens. *Flip RGB view* un-mirrors the preview.
* **Depth View (right):** a colour-coded depth map (warm = near, cool = far, dark = no reading) with a crosshair and a distance legend.
* **Read-outs (bottom):** nearest obstacle in the **Left / Ahead / Right** thirds of the image, the distance at the **Crosshair**, and the share of pixels with a valid depth reading. Distances are measured inside the band between the dashed lines, so the floor is not reported as an obstacle.

**Depth sources.** The app reads depth only from a camera that Android itself exposes as a depth sensor (Camera2 `DEPTH_OUTPUT`, `DEPTH16` frames). If the device exposes none, the Depth View says so and shows no data. The temi SDK has no depth-stream API, and temi's own depth sensors are handled by the robot's navigation computer, so on a temi the depth panel is likely to report *Not available*. To preview the layout without a sensor, switch on **Simulated robot** (pill on the second row): the panel then shows a clearly labelled (amber *SIMULATED*) synthetic scene.

The camera and depth stream only run while the app is on screen and are released when it goes to the background.

## Raise a hand to call temi

The RGB Camera panel also runs a pose model (`app/src/main/assets/movenet_lightning.onnx`, MoveNet Lightning, via ONNX Runtime) on the camera image and draws the detected skeleton. When a hand is raised, either a full arm stretched up or a half raise (elbow bent, forearm pointing up) and held for about 0.8 s, the badge changes to **HAND RAISED**.

* Switch on **Raise hand → follow me** (top right). When a raised hand is seen, temi says hello and starts its built-in follow mode (`beWithMe`), which keeps its own safe distance and avoids obstacles. It stops after 30 s, when you press **Stop following**, or when you leave the app.
* It is **off by default** and waits 10 s between triggers.
* The wrist must be clearly above the shoulder line and above its elbow. Hands at chest height, hands on hips, a T-pose and arms down do not count. Either hand works.
* Works best 1-3 m from the robot with the upper body in view. Pose detection only runs while the app is open.
* With **Simulated robot** on, the app shows the FOLLOWING state without moving anything, so the gesture can be tested on any tablet or phone.
* **Walk to me (on by default, toggle "Walk to me first"):** temi's own follow mode finds people with its depth sensors, so it only works at close range. With *Walk to me first* on, a raised hand makes temi walk to you using only the tablet camera, and only then (optional) start follow mode:

  1. temi says "I'm coming" and, twice a second, estimates your distance from the width of your shoulders in the picture and your direction from where you are in the frame.
  2. If you are more than about 10° off to one side it turns towards you (`turnBy`), otherwise it drives forward with temi's obstacle avoidance (`skidJoy` with *smart* on).
  3. It stops at the **Stop at** distance (1.0, 1.3, 1.6 or 2.0 m; tap the pill to change) and says "I'm here". It then starts follow mode for the usual 30 s, which works at that range. Switch this off with `approachThenFollow` in `AppSettings.kt` if you only want temi to walk up and wait.
  4. It gives up if it cannot see you for 4 s, after 60 s, or when you make the **T-pose**.
  * **Calibrate once:** stand exactly 2 m from temi, facing it with both shoulders visible, and tap **Calibrate (stand 2 m away)**. The pill row also shows a live "You: 2.0 m, 5° left" read-out to check it.
  * **If temi turns away from you,** switch on **Invert turn**.
  * **Range:** it is limited by how far the pose model can see you, roughly 3-4 m in a normal room (shoulders only a few pixels wide beyond that). Distance is an estimate: someone standing sideways looks closer than they are, and children or very broad people will be misjudged.
  * **First test:** clear floor, someone at the robot's Stop button, and the T-pose ready. Simulated robot mode only pretends to drive for 2.5 s.
* **Stop gesture (T-pose):** stretch both arms straight out to the sides at shoulder height and hold for about 0.8 s. temi says it will stop and ends follow mode (works even if *Raise hand → follow me* is off). The skeleton turns red and the badge shows **STOPPING**. A bent arm, one arm out, arms up or arms down do not count.
* **Find person:** switch on **Find person** and temi tilts its tablet slowly up and down (5° steps, about one per 1.2 s, between -15° and 45°) until the camera sees a head and both shoulders for a few frames in a row. It then stops, nudges the tilt so the head sits in the upper-middle of the picture, and stays there. If the person is gone for 4 s it starts searching again. It does not move the tablet while temi is following someone. The badge shows **SCANNING** or **PERSON FOUND**. Off by default.

## Hand Reading screen

The **Hand Reading** screen (sidebar) shows the tablet camera full-screen and reads **ASL fingerspelling**: you spell a word letter by letter with one hand, and temi says the word aloud. It does **not** need MediaPipe, so it runs on Android 6 (API 23).

* **Caption bar (bottom):** the letters spelled so far (large), the top guesses for the letter being shown, a hold progress message ("Hold C... 60%"), and the last few finished words. A badge (top left) shows `READY`, `SIGNING`, `LOADING MODEL` or `ERROR`. A green square on the picture shows the part of the image that is given to the model.
* **Speak: ON/OFF (top right):** whether temi says the word.
* **Simulated robot: ON/OFF (top right):** lets the app run on any tablet or phone. Nothing is spoken but the caption still works.
* **Flip hand (top right):** mirrors the hand picture before it is classified. Most alphabet datasets show a right hand; if you sign with your left hand, or letters come out as mirror images, switch it on.
* **Input 0..1 / -1..1 / 0..255 (top right):** how the model expects pixel values. A .tflite file does not say how its training images were scaled, so tap the pill until the guesses make sense (most models use `0..1` or `-1..1`; a model that rescales inside itself uses `0..255`). Models with `uint8` input ignore it. The choice is remembered.

### How a word is spelled

1. Raise a hand (wrist above the hips) and show a letter. The pose model finds the wrist and elbow, and a square is cut out just past the wrist.
2. The letter is added once it has been shown steadily for 0.8 s at 70% confidence or more. The same letter twice in a row (for example the two L in "HELLO") needs a short pause or a 1.5 s hold.
3. Lower your hand for about 1.8 s, or show the model's `space` sign, and the word is finished: it is shown and temi says it. A `del` sign removes the last letter.

**Scope:** American Sign Language **letters** only. J and Z need movement and will not be recognised properly by a single-picture model. No whole-word signs, no sentences, and no Thai or other sign languages. Alphabet models are usually trained on clean photos, so expect lower accuracy in a real room: good light, a plain background, hand about 1 m away and held still.

```
camera frame -> MoveNet pose (ONNX Runtime) -> wrist + elbow of the raised hand
             -> square crop past the wrist, scaled to the model's input size
             -> asl_alphabet.tflite -> letter
             -> hold 0.8 s -> letters -> word -> caption + temi.speak()
```

Code: `screens/SignLanguageScreen.kt` (UI), `utils/SignRecognizer.kt` (hand crop, letters to words, speaking), `utils/SignClassifier.kt` (TensorFlow Lite), `utils/PoseDetector.kt` (MoveNet).

### Model files

`app/src/main/assets/` must contain:

|File|What it is|
|-|-|
|`movenet_lightning.onnx` (9.4 MB)|pose model that finds the wrist and elbow. Already in the project.|
|`asl_alphabet.tflite` (335 KB)|an ASL alphabet image classifier. Already in the project; replace it with your own model if you like.|
|`asl_alphabet_labels.txt`|one label per line, in the model's output order. (The name must not end in `.txt.txt`; Windows hides the first `.txt`.)|

Requirements for the alphabet model: input `[1, height, width, 3]` (RGB) or `[1, height, width, 1]` (grayscale, e.g. 64x64), float32 or uint8, output `[1, classes]` (probabilities or logits, float or quantised). Size and types are read from the file. Without the labels file, **26 outputs are read as A-Z**, **27 as A-Z + nothing** and **29 outputs as A-Z, del, nothing, space** (the order of the Kaggle "ASL Alphabet" dataset); any other size needs the labels file. Label names `nothing`/`blank`/`none`/`background` are ignored, `space` ends a word and `del`/`delete` removes a letter. If the file is missing the screen says so.

If the letters look wrong, check, in this order: **Input** mode, **Flip hand**, and the order of the labels.

### Tuning

The thresholds are constants at the top of `utils/SignRecognizer.kt` (`LETTER_THRESHOLD`, `HOLD_MS`, `REPEAT_MS`, `WORD_GAP_MS`, ...). The on-screen `fps · ms` line shows the real speed (pose model plus classifier).

## Project structure

```
app/src/main/java/com/leslie/tot/
├── MainActivity.kt            starts settings and the temi link, shows the screen
├── screens/CameraDepthScreen.kt    RGB + depth panels, toggles, calibration
├── screens/SignLanguageScreen.kt   Hand Reading: camera, caption bar, toggles
├── components/                TopBar (temi battery), Sidebar (screen switcher)
├── utils/
│   ├── DepthCameraSource.kt   depth stream (Camera2 DEPTH16 or simulated), colouring and distance stats
│   ├── GestureEngine.kt       camera -> pose -> raised hand / T-pose / find person -> temi
│   ├── ApproachController.kt  walks temi to a person using the tablet camera (distance from shoulder width)
│   ├── PoseDetector.kt        MoveNet Lightning (ONNX Runtime), shared by both screens
│   ├── SignRecognizer.kt      hand crop, letters to words, speaking (Hand Reading)
│   ├── SignClassifier.kt      TFLite alphabet classifier (Hand Reading)
│   ├── GestureDetectors.kt    raised-hand and T-pose detectors
│   ├── TemiRepository.kt      the only class that uses the temi SDK (speech, follow, tilt, turn, drive, simulated robot)
│   └── AppSettings.kt         persisted settings
└── ui/theme/
app/src/main/assets/   movenet_lightning.onnx, asl_alphabet.tflite, asl_alphabet_labels.txt
```

## Troubleshooting

|Symptom|Check|
|-|-|
|RGB panel says "No permission"|Allow Camera for the app in Android Settings > Apps, then reopen the app.|
|Depth View says "Not available"|The device exposes no depth camera to apps (expected on temi). Use *Simulated robot* to preview the view.|
|RGB shows "too many cameras open"|The device cannot run two cameras at once, or another app holds the camera. Close other camera apps.|
|temi turns away from you|Switch on **Invert turn**, then calibrate.|
|Hand Reading letters look wrong|Check, in this order: **Input** mode, **Flip hand**, and the order of the labels.|

## Known limitations

* temi's follow mode (`beWithMe`) relies on the robot's own person detection, which only works at close range. That is why *Walk to me first* brings temi close before handing over to it.
* *Walk to me* judges distance from shoulder width in a single camera, so it is approximate and cannot see beyond a few metres. The first time, check which way temi turns (Invert turn) and calibrate.
* Test with the robot in a clear area and keep the Stop button within reach.

## Related apps

This project used to be one app. It was split into two separate apps, each with its own repository:

|App|Repo folder|What it does|
|-|-|-|
|**IAQ-IEQ**|`IAQ-IEQ`|Air-quality and comfort monitoring: dashboard, missions, routines, Google Sheets upload, voice bridge.|
|**temi-of-thinh (TOT)**|`temi-of-thinh`|A test and utility app for temi: camera and depth view, gesture control, and ASL hand reading.|

The apps do not depend on each other and can be installed side by side on the same temi.
