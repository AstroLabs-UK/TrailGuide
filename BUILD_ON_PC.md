# How to build the APK (when you get to a PC)

You are currently on a phone, so you cannot compile the app here.  
As soon as you have access to any Windows / Mac / Linux computer, follow these steps:

## 1. Get the project onto the computer
- Copy the entire `TrailGuide` folder (or the zip) to the computer.

## 2. Install Android Studio (free)
- Download from: https://developer.android.com/studio
- Install it (it will also install the Android SDK).

## 3. Open the project
- Open Android Studio
- Choose **Open** and select the `TrailGuide` folder
- Wait for Gradle to finish downloading libraries (first time can take a few minutes)

## 4. Build the APK
- Menu → **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- When it finishes, click the link that appears
- The file will be at:
  `TrailGuide/app/build/outputs/apk/debug/app-debug.apk`

## 5. Install on your phone
- Copy the APK to your phone
- Open it and allow installation from unknown sources if asked

---

That is the only way to get a real installable APK.  
I have prepared as much of the source code as possible so the build is ready the moment you reach a PC.
