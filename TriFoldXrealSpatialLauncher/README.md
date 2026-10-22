# TriFold XR Space — 3DoF Multi-Window Spatial Launcher for XREAL Air 2 Pro

Complete Android Studio project for the **Samsung Galaxy Z TriFold** and **XREAL Air 2 Pro** (`Shizuku` + `VirtualDisplay` + `OpenGL ES 3.0` + `USB HID 1,000Hz IMU` + `Curved Ultrawide Cylinder Mesh` + `3DoF Phone Laser Pointer`).

## How to Build the Installable `.apk`

### Option A: Build Locally in Android Studio (2 Minutes)
1. Unzip `TriFold_XREAL_3DoF_Launcher_Project.zip` and open the folder in **Android Studio Ladybug (or newer)**.
2. Let Gradle sync the dependencies (`Shizuku API 13.1.5`, `HiddenApiBypass 4.3`, `AndroidX`).
3. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Install `app/build/outputs/apk/debug/app-debug.apk` onto your Samsung Galaxy Z TriFold via `adb install app-debug.apk`.

### Option B: Zero-Install Cloud APK Build via GitHub Actions
1. Push this project folder to any free GitHub repository.
2. The included `.github/workflows/build-apk.yml` workflow automatically provisions JDK 17 + Android SDK 34 + Gradle 8.7 in the cloud and compiles both Debug and signed Release APKs.
3. Download `TriFold-XREAL-Spatial-Launcher-APK.zip` from the **Actions** tab directly on your Galaxy Z TriFold and tap to install.
