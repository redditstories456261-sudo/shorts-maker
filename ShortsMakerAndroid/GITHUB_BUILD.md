# Building the APK with no PC - using GitHub's free cloud build

This builds the real APK on GitHub's servers (free), triggered from your phone. You do this
**once** to get a basic APK; repeat the "push" step any time you change the code.

The native-setup items from the main `README.md` (whisper.cpp, models, fonts) still matter, but
some of them (whisper.cpp source) are fetched automatically by the cloud build. **AI model files
and fonts still need to go in the app**, see step 6.

## Step 1 - Create a free GitHub account
In your phone's browser: github.com -> Sign up.

## Step 2 - Create a new repository
github.com -> "+" (top right) -> New repository -> name it e.g. `shorts-maker` -> keep it
**Public** (Public repos get unlimited free GitHub Actions build minutes; Private repos only get
a limited free quota) -> Create repository. Leave it empty (don't add a README here).

## Step 3 - Create a Personal Access Token (this is your "password" for pushing code)
github.com -> tap your profile picture -> Settings -> scroll down to **Developer settings** ->
**Personal access tokens** -> **Tokens (classic)** -> Generate new token (classic) -> tick the
**repo** checkbox -> Generate -> **copy the token (starts with `ghp_`) and save it somewhere safe,
it's shown only once.**

## Step 4 - Install Termux (just to upload the project files - this is the only "technical" part)
Install **Termux** from **F-Droid** (not the old Play Store version, which is outdated):
https://f-droid.org/packages/com.termux/ (open this link on your phone, install F-Droid first if
you don't have it, then install Termux through it).

Open Termux and run these commands one at a time (replace `YOURNAME`, `YOURTOKEN`, `shorts-maker`
with your actual GitHub username, the token from Step 3, and your repo name):

```
pkg update -y && pkg install git -y
termux-setup-storage
```
(Termux will ask for a storage permission popup - allow it.)

```
cd ~/storage/downloads
unzip ShortsMakerAndroid.zip
cd ShortsMakerAndroid
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://YOURNAME:YOURTOKEN@github.com/YOURNAME/shorts-maker.git
git push -u origin main
```

If `unzip` says "command not found", run `pkg install unzip -y` first. If the zip isn't in your
Downloads folder, check where your browser/this chat saved it (often "Downloads" or "Files ->
Download").

## Step 5 - Watch it build
On github.com, open your repository -> **Actions** tab. You should see a "Build APK" run start
automatically (it was triggered by your push). Tap it to watch progress - it usually takes
**8-15 minutes** the first time.

- If it finishes with a green check: tap into the run -> scroll to **Artifacts** ->
  **ShortsMaker-debug-apk** -> download (it's a zip containing `app-debug.apk`).
- If it fails (red X): tap the failed step to see the error message. Paste that error back to me
  and I'll help you fix it - native Android builds often need one or two small adjustments the
  first time (an exact file/model name, a version mismatch, etc.).

## Step 6 - Add the AI models and fonts (still required - CI can't download these for you
automatically without extra setup)
The cloud build produces a **working app shell**, but it needs these files on the **phone itself**
(not in the repo - they're too big to push comfortably from a phone, and some are licensed
separately):
- Whisper model (`ggml-base.bin`) -> push to the installed app's files folder via Termux:
  `curl -L -o ggml-base.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin`
  then move it into the app's folder as described in `README.md` step 3.
- MediaPipe `face_detector.task` / `face_landmarker.task` -> download links in `README.md` step 5,
  then: `adb push` is normally used from a PC - without one, you can instead have the **app itself
  copy a file you picked** into place. A later version of `MainActivity.kt` should add a simple
  "Import model file" button using Android's file picker (like the existing "Pick a video file"
  button) so you can tap a downloaded file in your phone's Downloads and send it straight to the
  right app folder, no PC/adb needed. Ask me to add that button if you'd like it now.

## Step 7 - Install the APK
Unzip the downloaded artifact (any phone "Files" app with zip support, or Termux's `unzip`), tap
`app-debug.apk`, and allow "Install unknown apps" for your browser/file-manager app when prompted.

## Making changes later
Edit files in Termux (`nano <file>` is a simple in-terminal editor), then:
```
cd ~/storage/downloads/ShortsMakerAndroid
git add .
git commit -m "Update"
git push
```
GitHub Actions rebuilds automatically on every push.
