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
TRANSLATIONS=$(cat changed_texts.txt)


TRANSLATIONS="${TRANSLATIONS//$'\n'/' ; '}"
TRANSLATIONS="${TRANSLATIONS//$'\r'/' ; '}"

# First, replace all string-substitutions
TRANSLATIONS=$(echo $TRANSLATIONS | sed 's/%1$s//g')
TRANSLATIONS=$(echo $TRANSLATIONS | sed 's/%d//g')
TRANSLATIONS=$(echo $TRANSLATIONS | sed 's/%s//g')
TRANSLATIONS=$(echo $TRANSLATIONS | sed 's/"//g')

echo $TRANSLATIONS
