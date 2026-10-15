#!/usr/bin/env bash
set +e

echo "=== MÍNIMO: DIAGNÓSTICO SIN VOLVER A COMPILAR ==="
echo
if [[ -f /tmp/minimo-codespaces-build.log ]]; then
  echo "--- Errores relevantes del último intento ---"
  grep -niE 'OutOfMemory|out of memory|oom|SIGKILL|Killed|low memory|daemon disappeared|What went wrong|No space left|Exception|BUILD FAILED|FAILURE:' /tmp/minimo-codespaces-build.log | tail -35
  echo
  echo "--- Últimas líneas del log del build ---"
  tail -35 /tmp/minimo-codespaces-build.log
else
  echo "No existe /tmp/minimo-codespaces-build.log"
fi

echo
echo "--- Límites y estado de memoria del contenedor ---"
for f in /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.events /sys/fs/cgroup/cpu.max; do
  if [[ -r "$f" ]]; then
    echo "[$f]"
    cat "$f"
  fi
done

echo
echo "--- Espacio disponible ---"
df -h /workspaces/00157916 "$HOME" 2>/dev/null

echo
echo "--- Registro más reciente del daemon de Gradle ---"
f="$(ls -t "$HOME"/.gradle/daemon/9.3.1/daemon-*.out.log 2>/dev/null | head -1)"
if [[ -n "$f" ]]; then
  echo "Archivo: $f"
  grep -niE 'OutOfMemory|out of memory|fatal|exception|disappear|stop|shutdown|SIGKILL|Killed|memory|daemon.*started|daemon.*executing' "$f" | tail -35
  echo
  tail -25 "$f"
else
  echo "No se encontró un log del daemon de Gradle 9.3.1"
fi

echo
echo "=== Fin del diagnóstico. No se ha vuelto a ejecutar Gradle. ==="
