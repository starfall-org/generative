# Isolated stream-chunk CPU / allocation benchmark

This benchmark compiles the **production Kotlin sources** for `StreamResponseTextSplitter`,
`StreamCharBatcher`, `StreamTextSnapshot` and `ChatStreamDisplay`. It intentionally excludes
Compose, Markdown parsing and rendering, GPU work, networking and Room. Each scenario
validates the resulting length/count and reports medians across seven runs after warm-up.
It does not assert timing in automated tests; hardware performance assertions would be flaky.

Use an installed Kotlin CLI (tested: 2.4.21), Kotlin coroutines 1.10.2 (cached), Java and local Android SDK where appropriate;
no Gradle, dependency downloads, APK installation or GitHub Actions are needed.

From the repository root (the commands below can be run through `rtk run`):

```sh
mkdir -p /tmp/stream-chunk-bench
CORO=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2" -name 'kotlinx-coroutines-core-jvm-*.jar' | head -1)
COMMON="app/src/main/kotlin/org/starfall/generative/features/home/chat"
# The files under COMMON are built directly, not copied or re-implemented.
kotlinc -jvm-target 17 -cp "$CORO" -include-runtime   "$COMMON/logic/StreamCharBatcher.kt" "$COMMON/logic/StreamResponseTextSplitter.kt"   "$COMMON/logic/ChatStreamDisplay.kt" "$COMMON/model/ActiveChatStreamState.kt"   scripts/benchmarks/StreamChunkMicrobench.kt -d /tmp/stream-chunk-bench/host.jar
java -Xms128m -Xmx384m -cp "/tmp/stream-chunk-bench/host.jar:$CORO"   org.starfall.generative.features.home.chat.logic.StreamChunkMicrobenchKt
```

On a connected Android 11+ device (requires `d8`, `adb`, Android SDK platform 36,
which were available during the recorded run):

```sh
ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
KOTLIN_CP="$CORO:$ANDROID_HOME/platforms/android-36/android.jar"
kotlinc -jvm-target 1.8 -cp "$KOTLIN_CP" -include-runtime   "$COMMON/logic/StreamCharBatcher.kt" "$COMMON/logic/StreamResponseTextSplitter.kt"   "$COMMON/logic/ChatStreamDisplay.kt" "$COMMON/model/ActiveChatStreamState.kt"   scripts/benchmarks/StreamChunkMicrobenchAndroid.kt   -d /tmp/stream-chunk-bench/android-classes.jar
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 30   --lib "$ANDROID_HOME/platforms/android-36/android.jar"   --output /tmp/stream-chunk-bench/android-dex.jar   /tmp/stream-chunk-bench/android-classes.jar "$CORO"
adb push /tmp/stream-chunk-bench/android-dex.jar /data/local/tmp/stream-chunk-profile.dex.jar
adb shell 'CLASSPATH=/data/local/tmp/stream-chunk-profile.dex.jar app_process /system/bin org.starfall.generative.features.home.chat.logic.StreamChunkMicrobenchAndroidKt'
adb shell rm -f /data/local/tmp/stream-chunk-profile.dex.jar
```

Kotlin 2.4 metadata may generate noisy D8 warnings with build-tools 36; the
recorded DEX completed and executed normally despite warnings. Redirect D8's
stderr to a temporary log if necessary. These commands execute a separate
Android shell process; they do not install or alter the Generative app.

**Interpretation:** The `displayPaced` scenario feeds 20,000 fragments as a
CPU stress burst with the production 50 ms commit interval; `displayFrequentFlush`
forces a pending flush once per 20 fragments (1,000 forced publishes). Neither
simulates network wall-clock cadence, but the latter stress case deliberately
exceeds the normal UI commit rate. Samples measure thread CPU time and thread
allocation (both exclude UI, graphics and other threads), plus wall time. See
`app/docs/stream-hardware-audit.md` for measured results and scope limitations.
