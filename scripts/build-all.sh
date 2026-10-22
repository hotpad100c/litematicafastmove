#!/usr/bin/env bash
# Build every Minecraft version of the mod and collect the jars in dist/.
#
#   scripts/build-all.sh                 main branch + every local port/<mc> branch
#   scripts/build-all.sh 1.21.11 1.20.1  only these ("main" = the current checkout)
#
# Each port/<mc> branch is checked out once as a worktree under worktrees/<mc> (git-ignored) and reused afterwards.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
mkdir -p "$DIST" "$ROOT/worktrees"

if [ $# -gt 0 ]; then
    targets=("$@")
else
    targets=(main $(git -C "$ROOT" for-each-ref --format='%(refname:strip=3)' 'refs/heads/port/*' | sort -V -r))
fi

declare -A result
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

    echo "==> Building $mc"
    rm -rf "$dir/build/libs"
    if (cd "$dir" && ./gradlew build --console=plain) > "$DIST/build-$mc.log" 2>&1; then
        for jar in "$dir"/build/libs/*.jar; do
            case "$jar" in *-sources.jar|*-dev.jar) ;; *) cp "$jar" "$DIST/" ;; esac
        done
        result[$mc]="OK"
        rm -f "$DIST/build-$mc.log"
    else
        result[$mc]="FAILED (see dist/build-$mc.log)"
    fi
done

echo
failed=0
for mc in "${targets[@]}"; do
    printf '%-10s %s\n' "$mc" "${result[$mc]}"
    case "${result[$mc]}" in OK) ;; *) failed=1 ;; esac
done
exit $failed
