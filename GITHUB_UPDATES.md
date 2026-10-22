# GitHub updates for Trail Guide

## One-time setup

1. Create a GitHub repo (public is easiest for free API access).
2. Push this project to the repo.
3. In Android Studio: **Build → Generate Signed Bundle / APK** (or use debug APK for personal use).
4. On GitHub: **Releases → Draft a new release**
   - Tag: `v0.7.2` (must be higher than the installed versionName)
   - Upload `app-release.apk` or `app-debug.apk` as a release asset
5. In the app: **Settings → Updates**
   - Repo: `yourusername/TrailGuide`
   - Tap **Check for updates**

## How it works

- App calls `https://api.github.com/repos/OWNER/REPO/releases/latest`
- Compares release tag to the installed `versionName`
- If newer, opens the APK download (or release page) in the browser
- User installs the APK (may need “Install unknown apps” permission)

## CI idea (optional)

Use GitHub Actions to build the APK on each tag and attach it to the release automatically.

## Limits

- Not silent background Play Store updates
- User must confirm install
- Private repos need a token (not implemented; use public repo for personal updates)
