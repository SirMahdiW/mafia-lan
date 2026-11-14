#!/usr/bin/env bash
# =============================================================================
#  اجرای تست خط فرمان پروژه (test/TestMain.kt) روی JVM
# -----------------------------------------------------------------------------
#  این تست هیچ وابستگی‌ای به Android ندارد: سرور واقعی Kotlin را بالا می‌آورد.
#  کامپایلر Kotlin از کش Gradle خوانده می‌شود (پس از یک بار build).
#  خروجی موفق تست = چاپ «up» روی خروجی استاندارد.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUT="app/build/cli-test"
LOG="app/build/cli-test.log"
rm -rf "$OUT"; mkdir -p "$OUT"

GH="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    SEP=";"
    if command -v cygpath >/dev/null 2>&1; then
      GH="$(cygpath -u "$GH")"
      native() { cygpath -w "$1"; }
    else
      native() { printf '%s' "$1"; }
    fi
    ;;
  *)
    SEP=":"
    native() { printf '%s' "$1"; }
    ;;
esac

find_jar() { # $1 = مسیر گروه/artifact در کش Gradle
  find "$GH/$1" -name '*.jar' 2>/dev/null | grep -v -e '-sources' -e '-javadoc' | sort | tail -1
}

JARS=()
for art in \
  org.jetbrains.kotlin/kotlin-compiler-embeddable \
  org.jetbrains.kotlin/kotlin-stdlib \
  org.jetbrains.kotlin/kotlin-script-runtime \
  org.jetbrains.kotlin/kotlin-reflect \
  org.jetbrains.kotlin/kotlin-daemon-embeddable \
  org.jetbrains.intellij.deps/trove4j \
  org.jetbrains/annotations ; do
  j="$(find_jar "$art")"
  [ -n "$j" ] && JARS+=("$(native "$j")")
done

if [ "${#JARS[@]}" -lt 2 ]; then
  echo "::error::کامپایلر Kotlin در کش Gradle پیدا نشد. ابتدا یک بار build اجرا کنید."
  exit 1
fi

CP="$(IFS="$SEP"; echo "${JARS[*]}")"

echo ">> کامپایل تست خط فرمان ..."
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath "$CP" -jvm-target 17 -d "$OUT" \
  app/src/main/java/ir/mafia/Engine.kt \
  app/src/main/java/ir/mafia/Json.kt \
  app/src/main/java/ir/mafia/Server.kt \
  test/TestMain.kt

echo ">> اجرای تست (سرور باید روی پورت ۳۰۵۵ بالا بیاید) ..."
# سرور تست پس از چاپ «up» بی‌نهایت اجرا می‌ماند؛ پس با timeout متوقف می‌شود.
timeout 25 java -cp "$OUT$SEP$CP" TestMainKt > "$LOG" 2>&1 || true

if grep -q '^up$' "$LOG"; then
  echo ">> PASS: تست خط فرمان موفق بود (خروجی: up)"
  exit 0
fi

echo "::error::تست خط فرمان شکست خورد. خروجی:"
cat "$LOG"
exit 1
