#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$HOME/.local/gradle/gradle-9.3.1/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

LOG="/tmp/minimo-codespaces-build.log"
GRADLE_JVM_ARGS="-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8"

echo "=== Mínimo: diagnóstico local de Codespaces ==="
echo "Raíz: $ROOT"
echo "Java:"
java -version 2>&1 | head -3
echo
echo "Gradle:"
gradle --version | grep -E 'Gradle |JVM:|Launcher JVM|Daemon JVM' || true
echo
echo "Memoria:"
free -h || true
echo
echo "Disco:"
df -h "$ROOT" "$HOME" || true
echo
echo "SDK requerido:"
for p in "$ANDROID_HOME/platforms/android-36.1" "$ANDROID_HOME/build-tools/36.0.0" "$ANDROID_HOME/platform-tools"; do
  if [[ -d "$p" ]]; then echo "OK  $p"; else echo "FALTA $p"; fi
done

if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "ERROR: no se encuentra sdkmanager en $ANDROID_HOME/cmdline-tools/latest/bin."
  exit 2
fi
if [[ ! -d "$ANDROID_HOME/platforms/android-36.1" || ! -d "$ANDROID_HOME/build-tools/36.0.0" ]]; then
  echo "ERROR: falta la plataforma o Build Tools requeridas; no se inicia Gradle."
  exit 2
fi

# Only restore the existing stable signing key when it is supplied as a Codespaces secret.
# Never generate a different key silently: that would prevent updating the already-installed app.
if [[ -n "${DEBUG_KEYSTORE_BASE64:-}" && ! -s "$ROOT/debug.keystore" ]]; then
  printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 --decode > "$ROOT/debug.keystore"
fi

run_gradle() {
  local rc
  echo
  echo "=== Ejecutando: gradle $* ==="
  set +e
  gradle "$@" --no-daemon --max-workers=1 --no-configuration-cache \
    "-Dorg.gradle.jvmargs=$GRADLE_JVM_ARGS" \
    -Pkotlin.compiler.execution.strategy=in-process \
    --stacktrace --console=plain 2>&1 | tee "$LOG"
  rc=${PIPESTATUS[0]}
  set -e
  if [[ "$rc" -ne 0 ]]; then
    echo
    echo "=== RESUMEN DEL ERROR ==="
    grep -inE 'OutOfMemory|Could not|What went wrong|error:|FAILURE:|daemon disappeared|Killed|No space left|SDK location|Exception' "$LOG" | tail -50 || true
    echo
    echo "=== ÚLTIMOS REGISTROS DEL DAEMON ==="
    local files
    files="$(ls -t "$HOME"/.gradle/daemon/9.3.1/daemon-*.out.log 2>/dev/null | head -2 || true)"
    if [[ -n "$files" ]]; then
      while IFS= read -r f; do
        [[ -n "$f" ]] || continue
        echo "--- $f"
        tail -35 "$f" || true
      done <<< "$files"
    else
      echo "No hay registros del daemon de Gradle 9.3.1."
    fi
    echo
    echo "Log completo guardado en: $LOG"
  fi
  return "$rc"
}

if ! run_gradle :app:testDebugUnitTest; then
  echo "RESULTADO: las pruebas unitarias no terminaron correctamente. No se intentará compilar el APK todavía."
  exit 1
fi

echo
echo "RESULTADO: pruebas unitarias completadas correctamente."

if [[ ! -s "$ROOT/debug.keystore" ]]; then
  echo "APK OMITIDO: no existe la clave de firma estable debug.keystore en este Codespace."
  echo "Para no poner en riesgo la actualización de la app instalada, no se genera una clave distinta."
  echo "El resultado de las pruebas está confirmado; para empaquetar con la misma firma hace falta la clave estable."
  exit 3
fi

if ! run_gradle :app:assembleDebug -PVERSION_CODE=20261015 -PVERSION_NAME=1.5-codespaces; then
  echo "RESULTADO: las pruebas pasaron, pero falló el empaquetado del APK."
  exit 1
fi

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
  echo "ERROR: Gradle terminó, pero no se encontró un APK válido en $APK."
  exit 4
fi

APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --verbose --print-certs "$APK"
fi
echo
sha256sum "$APK"
ls -lh "$APK"
echo "APK generado en: $APK"
