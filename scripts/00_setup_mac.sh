#!/bin/bash
# One time, on the Mac: JDK 21 and the Android SDK (platform 36), for building the launcher.
#   bash scripts/00_setup_mac.sh               install the tools
#   bash scripts/00_setup_mac.sh --gradle-zip  also download Gradle once (if the build's own download times out)
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$ROOT/config.sh"
[ -f "$CONFIG" ] || cp "$ROOT/config.example.sh" "$CONFIG"

echo "1/3 Installing JDK 21 and the Android command-line tools (Homebrew)..."
command -v brew >/dev/null || { echo "Homebrew is needed first: https://brew.sh"; exit 1; }
brew install openjdk@21 android-commandlinetools android-platform-tools
JDK="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
SDK="$(brew --prefix)/share/android-commandlinetools"

echo "2/3 Installing Android platform 36 and build tools (about 1 GB)..."
export JAVA_HOME="$JDK" ANDROID_HOME="$SDK" PATH="$JDK/bin:$PATH"
yes | sdkmanager --licenses >/dev/null
sdkmanager "platforms;android-36" "build-tools;36.0.0"

# Later lines win when config.sh is read, so appending keeps it simple.
{
  echo ""
  echo "# Set by 00_setup_mac.sh"
  echo "JAVA_HOME=\"$JDK\""
  echo "ANDROID_HOME=\"$SDK\""
} >> "$CONFIG"

if [ "$1" = "--gradle-zip" ]; then
  echo "3/3 Downloading Gradle 9.4.1 once (about 130 MB)..."
  mkdir -p "$ROOT/_local"
  ZIP="$ROOT/_local/gradle-9.4.1-bin.zip"
  [ -s "$ZIP" ] || curl -fL --retry 3 --connect-timeout 30 -o "$ZIP" https://services.gradle.org/distributions/gradle-9.4.1-bin.zip
  echo "GRADLE_ZIP=\"$ZIP\"" >> "$CONFIG"
else
  echo "3/3 Gradle downloads itself on the first build."
fi
echo "Done. Settings saved in config.sh."
