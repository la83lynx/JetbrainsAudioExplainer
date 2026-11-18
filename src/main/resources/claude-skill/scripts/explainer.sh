#!/bin/bash
# Helper for Claude to communicate with the Audio Explainer IDE plugin.
# Usage:
#   explainer.sh plan <json_file>      Send walkthrough plan from file
#   explainer.sh send <json_string>    Send raw JSON message
#   explainer.sh state                 Get current walkthrough state
#   explainer.sh stop                  Stop the walkthrough
#   explainer.sh save [name]           Save current walkthrough
#   explainer.sh load <name>           Load a saved walkthrough
#   explainer.sh list                  List saved walkthroughs

PORT_FILE="$HOME/.claude-explainer-port"
TOKEN_FILE="$HOME/.claude-explainer-token"

if [ ! -f "$PORT_FILE" ]; then
    echo '{"error": "Audio Explainer plugin not running (no port file)"}' >&2
    exit 1
fi

PORT=$(cat "$PORT_FILE")
BASE="http://127.0.0.1:$PORT"

if [ -f "$TOKEN_FILE" ]; then
    TOKEN=$(cat "$TOKEN_FILE")
    AUTH_HEADER="Authorization: Bearer $TOKEN"
else
    echo '{"error": "No auth token found"}' >&2
    exit 1
fi

# Percent-encode every byte of $1 (the plugin URL-decodes it as UTF-8).
url_encode() {
    printf '%s' "$1" | od -An -v -tx1 | tr -d ' 
' | sed 's/../%&/g'
}

# The plugin serves every project window of the IDE; this folder says which one. `pwd -W` is git-bash's
# Windows form of it, which is what the IDE's project paths look like there.
WS_HEADER="X-Explainer-Workspace: $(url_encode "$(pwd -W 2>/dev/null || pwd)")"

# Escape a string for use inside a JSON string literal (backslash and double quote).
json_escape() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

case "$1" in
    plan)
        if [ -z "$2" ]; then
            echo "Usage: explainer.sh plan <json_file>" >&2
            exit 1
        fi
        curl -s -X POST "$BASE/api/message" \
            -H 'Content-Type: application/json' \
            -H "$AUTH_HEADER" -H "$WS_HEADER" \
            --data-binary @"$2"
        ;;
    send)
        if [ -z "$2" ]; then
            echo "Usage: explainer.sh send '<json>'" >&2
            exit 1
        fi
        curl -s -X POST "$BASE/api/message" \
            -H 'Content-Type: application/json' \
            -H "$AUTH_HEADER" -H "$WS_HEADER" \
            -d "$2"
        ;;
    state)
        curl -s -H "$AUTH_HEADER" -H "$WS_HEADER" "$BASE/api/state"
        ;;
    stop)
        curl -s -X POST "$BASE/api/message" \
            -H 'Content-Type: application/json' \
            -H "$AUTH_HEADER" -H "$WS_HEADER" \
            -d '{"type": "stop"}'
        ;;
    save)
        NAME=$(json_escape "${2:-}")
        if [ -n "$NAME" ]; then
            curl -s -X POST "$BASE/api/save" \
                -H 'Content-Type: application/json' \
                -H "$AUTH_HEADER" -H "$WS_HEADER" \
                -d "{\"name\": \"$NAME\"}"
        else
            curl -s -X POST "$BASE/api/save" \
                -H 'Content-Type: application/json' \
                -H "$AUTH_HEADER" -H "$WS_HEADER" \
                -d '{}'
        fi
        ;;
    load)
        if [ -z "$2" ]; then
            echo "Usage: explainer.sh load <name>" >&2
            exit 1
        fi
        curl -s -X POST "$BASE/api/load" \
            -H 'Content-Type: application/json' \
            -H "$AUTH_HEADER" -H "$WS_HEADER" \
            -d "{\"name\": \"$(json_escape "$2")\"}"
        ;;
    list)
        curl -s -H "$AUTH_HEADER" -H "$WS_HEADER" "$BASE/api/walkthroughs"
        ;;
    *)
        echo "Usage: explainer.sh {plan|send|state|stop|save|load|list}" >&2
        exit 1
        ;;
esac
