#!/usr/bin/env bash
# Reproducible integration with upstream Gradle wrapper (does not modify repository source).
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$ROOT/.build/aliucord-template}"
[[ ! -e "$OUT" ]] || { echo "Destino ja existe: $OUT" >&2; exit 2; }
command -v git >/dev/null || { echo 'git ausente' >&2; exit 2; }
command -v java >/dev/null || { echo 'Java 21 ausente' >&2; exit 2; }
mkdir -p "$(dirname "$OUT")"
git clone --depth 1 https://github.com/Aliucord/plugins-template.git "$OUT"
for f in build.gradle.kts settings.gradle.kts gradle.properties; do cp -f "$ROOT/$f" "$OUT/$f"; done
mkdir -p "$OUT/gradle" "$OUT/plugins"
cp -f "$ROOT/gradle/libs.versions.toml" "$OUT/gradle/libs.versions.toml"
cp -f "$ROOT/plugins/build.gradle.kts" "$OUT/plugins/build.gradle.kts"
cp -a "$ROOT/plugins/WonderTranslate" "$OUT/plugins/WonderTranslate"
(cd "$OUT" && bash ./gradlew :plugins:WonderTranslate:make)
echo "Compilado: $OUT/plugins/WonderTranslate/build/outputs/WonderTranslate.zip"
