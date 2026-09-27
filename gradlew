#!/usr/bin/env sh
# ponytail: no committed gradle-wrapper.jar (a binary blob in git that nobody
# reviews). Downloads the distribution on demand instead. If you ever need the
# stock wrapper for Android Studio, run `./gradlew wrapper` once.
set -e

VERSION=8.13
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/dists"
DIR="$CACHE/gradle-$VERSION"

if [ ! -x "$DIR/gradle-$VERSION/bin/gradle" ]; then
    echo "Downloading Gradle $VERSION..." >&2
    mkdir -p "$DIR"
    curl -sSL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$DIR/gradle.zip"
    unzip -q -o "$DIR/gradle.zip" -d "$DIR"
    rm -f "$DIR/gradle.zip"
    chmod +x "$DIR/gradle-$VERSION/bin/gradle"
fi

exec "$DIR/gradle-$VERSION/bin/gradle" "$@"
