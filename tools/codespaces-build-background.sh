#!/usr/bin/env bash
set -u
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

LOG="/tmp/minimo-codespaces-background.log"
PID_FILE="/tmp/minimo-codespaces-background.pid"
STATUS_FILE="/tmp/minimo-codespaces-background.status"

case "${1:-status}" in
  start)
    if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "Ya hay una compilación ejecutándose (PID $(cat "$PID_FILE"))."
      echo "Usa: bash tools/codespaces-build-background.sh status"
      exit 0
    fi
    rm -f "$LOG" "$PID_FILE" "$STATUS_FILE"
    nohup bash -c '
      bash tools/codespaces-build.sh
      rc=$?
      printf "%s\n" "$rc" > /tmp/minimo-codespaces-background.status
      exit "$rc"
    ' > "$LOG" 2>&1 < /dev/null &
    pid=$!
    printf '%s\n' "$pid" > "$PID_FILE"
    sleep 1
    if kill -0 "$pid" 2>/dev/null; then
      echo "Compilación en segundo plano iniciada. PID=$pid"
      echo "No cierres Codespaces expresamente. Puedes comprobar el progreso con:"
      echo "bash tools/codespaces-build-background.sh status"
    else
      echo "El proceso se ha detenido al arrancar. Últimas líneas:"
      tail -35 "$LOG"
      exit 1
    fi
    ;;
  status)
    if [[ -f "$STATUS_FILE" ]]; then
      rc="$(cat "$STATUS_FILE")"
      case "$rc" in
        0) echo "RESULTADO: pruebas y compilación finalizaron correctamente." ;;
        3) echo "RESULTADO: pruebas correctas, pero no se generó el APK porque falta la clave de firma estable." ;;
        *) echo "RESULTADO: proceso terminado con código $rc." ;;
      esac
      tail -45 "$LOG"
      exit 0
    fi
    if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "La tarea sigue ejecutándose (PID $(cat "$PID_FILE"))."
      echo "--- Últimas líneas ---"
      tail -18 "$LOG"
    else
      echo "No se detecta proceso activo y no hay código final registrado."
      echo "Es posible que el entorno haya terminado el proceso."
      echo "--- Últimas líneas del registro ---"
      tail -45 "$LOG" 2>/dev/null || true
    fi
    ;;
  logs)
    tail -100 "$LOG" 2>/dev/null || echo "Aún no hay registro."
    ;;
  *)
    echo "Uso: bash tools/codespaces-build-background.sh {start|status|logs}"
    exit 2
    ;;
esac
