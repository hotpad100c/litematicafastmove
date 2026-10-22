#!/usr/bin/env bash
# Build every Minecraft version of the mod and collect the jars in dist/.
#
#   scripts/build-all.sh                 main branch + every local port/<mc> branch
#   scripts/build-all.sh 1.21.11 1.20.1  only these ("main" = the current checkout)
#   scripts/build-all.sh --pull ...      fetch origin first and fast-forward each branch to it
#   JOBS=3 scripts/build-all.sh          run at most 3 gradle builds at once (default 6)
#
# Each port/<mc> branch is checked out once as a worktree under worktrees/<mc> (git-ignored) and reused afterwards.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
JOBS="${JOBS:-6}"
STATUS="$DIST/.status"
mkdir -p "$DIST" "$ROOT/worktrees"
rm -rf "$STATUS"; mkdir -p "$STATUS"

pull=0
if [ "${1:-}" = --pull ]; then
    pull=1; shift
    git -C "$ROOT" fetch origin || { echo "fetch failed"; exit 1; }
fi

if [ $# -gt 0 ]; then
    targets=("$@")
else
    targets=(main $(git -C "$ROOT" for-each-ref --format='%(refname:strip=3)' 'refs/heads/port/*' | sort -V -r))
fi

declare -A result pulled
for mc in "${targets[@]}"; do
    if [ "$mc" = main ]; then
        dir="$ROOT"
    else
        dir="$ROOT/worktrees/$mc"
        if [ ! -d "$dir" ]; then
            if ! git -C "$ROOT" show-ref --verify --quiet "refs/heads/port/$mc"; then
                result[$mc]="no branch port/$mc"; continue
            fi
            git -C "$ROOT" worktree add "$dir" "port/$mc" >/dev/null || { result[$mc]="worktree failed"; continue; }
        fi
    fi

    if [ $pull = 1 ]; then
        branch="$(git -C "$dir" symbolic-ref --short HEAD)"
        before="$(git -C "$dir" rev-parse --short HEAD)"
        if git -C "$dir" merge --ff-only --quiet "origin/$branch" 2>/dev/null; then
            after="$(git -C "$dir" rev-parse --short HEAD)"
            [ "$before" != "$after" ] && pulled[$mc]="(pulled $before..$after)"
        else
            pulled[$mc]="(NOT pulled: diverged or local changes in the way)"
        fi
    fi

    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n; done
    echo "==> Building $mc"
    (
        rm -rf "$dir/build/libs"
        if (cd "$dir" && ./gradlew build --console=plain) > "$DIST/build-$mc.log" 2>&1; then
            for jar in "$dir"/build/libs/*.jar; do
                case "$jar" in *-sources.jar|*-dev.jar) ;; *) cp "$jar" "$DIST/" ;; esac
            done
            echo OK > "$STATUS/$mc"
            rm -f "$DIST/build-$mc.log"
        else
            echo "FAILED (see dist/build-$mc.log)" > "$STATUS/$mc"
        fi
        echo "<== $mc: $(cat "$STATUS/$mc")"
    ) &
done
wait

echo
failed=0
for mc in "${targets[@]}"; do
    [ -f "$STATUS/$mc" ] && result[$mc]="$(cat "$STATUS/$mc")"
    printf '%-10s %s %s\n' "$mc" "${result[$mc]}" "${pulled[$mc]:-}"
    case "${result[$mc]}" in OK) ;; *) failed=1 ;; esac
done
rm -rf "$STATUS"
exit $failed
