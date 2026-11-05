#!/bin/bash
# Jenkins gate before publishing: waits for the GitHub Actions check "verify" (see
# .github/workflows/build.yml) of $GIT_COMMIT and fails if it fails, is missing or takes longer
# than GATE_POLLS x GATE_SLEEP seconds (default 60 x 30 s). The repository is public, so the
# checks API needs no token.
api="https://api.github.com/repos/geckoprojects-org/org.gecko.messaging/commits/${GIT_COMMIT}/check-runs?check_name=verify"
for i in $(seq 1 ${GATE_POLLS:-60}); do
    json=$(curl -fsS -H 'Accept: application/vnd.github+json' "$api") || json=''
    status=$(printf '%s' "$json" | grep -o '"status": *"[a-z_]*"' | head -1 | sed 's/.*"\([a-z_]*\)"$/\1/')
    conclusion=$(printf '%s' "$json" | grep -o '"conclusion": *"[a-z_]*"' | head -1 | sed 's/.*"\([a-z_]*\)"$/\1/')
    echo "GitHub check verify for ${GIT_COMMIT}: status=${status:-none} conclusion=${conclusion:-none}"
    if [ "$status" = completed ]; then
        [ "$conclusion" = success ] && exit 0
        echo "GitHub CI did not succeed, not publishing" >&2; exit 1
    fi
    sleep ${GATE_SLEEP:-30}
done
echo "GitHub CI did not finish in time, not publishing" >&2; exit 1
