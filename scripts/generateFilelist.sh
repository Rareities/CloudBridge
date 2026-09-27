#!/bin/bash

if [ "$#" -lt 2 ]; then
    echo "Pass the amount of commits and at least one translation file!"
    exit 1
fi

COMMITS="$1"
shift
if ! [[ "$COMMITS" =~ ^[1-9][0-9]*$ ]]; then
    echo "The commit count must be a positive integer!"
    exit 1
fi

# Accept every changed translation file from the workflow and keep the path list
# after `--` so a filename cannot be interpreted as a Git option.
DIFF=$(git diff -U0 "HEAD~${COMMITS}" -- "$@" | grep -E "^\+" | grep -v +++ | cut -c 2- | sed 's/^[ \t]*\(.*$\)/\1/')
echo "<xml>$DIFF</xml>" | xmlstarlet sel -t -m '//string' -v . -n > changed_texts.txt

# Google Translate's public endpoint accepts at most 5,000 characters. Keep
# each CI request below that limit while preserving every changed string across
# the matrix chunks. The default chunk size leaves room for separators.
CHUNK_INDEX="${TRANSLATION_CHUNK_INDEX:-1}"
CHUNK_SIZE="${TRANSLATION_CHUNK_SIZE:-4500}"
if ! [[ "$CHUNK_INDEX" =~ ^[1-9][0-9]*$ ]] || ! [[ "$CHUNK_SIZE" =~ ^[1-9][0-9]*$ ]]; then
    echo "Translation chunk index and size must be positive integers!"
    exit 1
fi

mapfile -t SOURCE_LINES < changed_texts.txt
CHUNK=1
CURRENT=""
SELECTED=""
for SOURCE_LINE in "${SOURCE_LINES[@]}"; do
    # First, replace string substitutions and quotes before measuring size.
    LINE=$(printf '%s' "$SOURCE_LINE" | sed 's/%1\$s//g; s/%d//g; s/%s//g; s/"//g')
    [ -z "$LINE" ] && continue
    if [ -n "$CURRENT" ]; then
        CANDIDATE="$CURRENT ; $LINE"
    else
        CANDIDATE="$LINE"
    fi
    if [ -n "$CURRENT" ] && [ "${#CANDIDATE}" -gt "$CHUNK_SIZE" ]; then
        if [ "$CHUNK" -eq "$CHUNK_INDEX" ]; then
            SELECTED="$CURRENT"
        fi
        CHUNK=$((CHUNK + 1))
        CURRENT="$LINE"
    else
        CURRENT="$CANDIDATE"
    fi
done
if [ -n "$CURRENT" ] && [ "$CHUNK" -eq "$CHUNK_INDEX" ]; then
    SELECTED="$CURRENT"
fi

printf '%s\n' "$SELECTED"
