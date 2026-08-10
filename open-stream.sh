#!/bin/bash

set -e

if [ -z "$1" ]; then
    echo "Usage: bash open-stream.sh <QUEST_IP>"
    exit 1
fi

IP="$1"
URL="http://$IP:8080/stream"

echo "Opening $URL"
open "$URL"
