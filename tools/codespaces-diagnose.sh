#!/usr/bin/env bash
set +e
echo "=== DIAGNÓSTICO MÍNIMO (NO COMPILA) ==="

echo
echo "[MEMORIA]"
for f in /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.events /sys/fs/cgroup/cpu.max; do
  if [[ -r "$f" ]]; then printf '%s: ' "$f"; tr '\n' ' ' < "$f"; echo; fi
done

echo
echo "[DISCO]"
df -h /workspaces/00157916 2>/dev/null | tail -1

echo
echo "[ERRORES DEL ÚLTIMO BUILD]"
if [[ -f /tmp/minimo-codespaces-build.log ]]; then
  grep -hiE 'OutOfMemory|out of memory|oom_kill|SIGKILL|Killed|No space left|What went wrong|BUILD FAILED|FAILURE:|SDK location|requires.*Java|Unsupported class|Could not resolve|Compilation error|e: file:|failed' /tmp/minimo-codespaces-build.log | tail -12 | cut -c1-220
else
  echo "No existe /tmp/minimo-codespaces-build.log"
fi

echo
echo "[ULTIMO LOG DE GRADLE]"
f="$(ls -t "$HOME"/.gradle/daemon/9.3.1/daemon-*.out.log 2>/dev/null | head -1)"
if [[ -n "$f" ]]; then
  echo "Archivo: $(basename "$f")"
  grep -hiE 'OutOfMemory|out of memory|fatal|exception|disappear|SIGKILL|Killed|unable to create native thread|Could not reserve enough space' "$f" | tail -8 | cut -c1-220
  echo "Última línea:"
  tail -1 "$f" | cut -c1-220
else
  echo "No se encontró log de Gradle 9.3.1"
fi
echo
echo "=== FIN. No se ha ejecutado Gradle. ==="
