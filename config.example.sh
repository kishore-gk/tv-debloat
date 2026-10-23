# Copy to config.sh (kept out of git) and fill in. Every script reads it.

# The TV's address for adb: its IP and port. scripts/01_connect.sh sets this for you (port 5555).
TV="192.168.1.50:5555"

# Build tools. scripts/00_setup_mac.sh fills these in.
JAVA_HOME=""
ANDROID_HOME=""

# Optional: a local copy of gradle-9.4.1-bin.zip, if Gradle's own download keeps timing out.
# scripts/00_setup_mac.sh downloads one to _local/ when you pass --gradle-zip.
GRADLE_ZIP=""
