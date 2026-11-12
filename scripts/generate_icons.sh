#!/bin/bash
set -euo pipefail

# Generate all branding assets from the single app logo resource.
logo="app/src/main/res/drawable-nodpi/generative_logo.png"
declare -A sizes=( ["mdpi"]="48x48" ["hdpi"]="72x72" ["xhdpi"]="96x96" ["xxhdpi"]="144x144" ["xxxhdpi"]="192x192" )
declare -A notification_sizes=( ["mdpi"]=24 ["hdpi"]=36 ["xhdpi"]=48 ["xxhdpi"]=72 ["xxxhdpi"]=96 )

for density in "${!sizes[@]}"; do
    size=${sizes[$density]}
    artwork=$(( ${size%x*} * 82 / 100 ))
    for mode in light night; do
        background=white
        if [[ "$mode" == "night" ]]; then
            dir="app/src/main/res/mipmap-night-$density"
            background=black
        else
            dir="app/src/main/res/mipmap-$density"
        fi
        mkdir -p "$dir"
        convert "$logo" -trim +repage -resize "${artwork}x${artwork}" -gravity center -background "$background" -extent "$size" "$dir/ic_launcher.png"
        cp "$dir/ic_launcher.png" "$dir/ic_launcher_round.png"
    done

    # Android tints small notification icons; retain the galaxy's opaque silhouette.
    notification_size=${notification_sizes[$density]}
    notification_artwork=$(( notification_size * 20 / 24 ))
    notification_dir="app/src/main/res/drawable-$density"
    mkdir -p "$notification_dir"
    convert "$logo" -trim +repage -alpha extract -threshold 86.27% -alpha copy \
        -channel RGB -evaluate set 100% +channel -resize "${notification_artwork}x${notification_artwork}" \
        -gravity center -background none -extent "${notification_size}x${notification_size}" \
        "$notification_dir/ic_generative_logo.png"
done

# Keep documentation artwork and the small website icon in sync.
cp "$logo" docs/generative_logo.png
convert "$logo" -resize 256x256 -strip docs/icon.png
