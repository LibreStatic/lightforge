#!/bin/sh
# usage: build_sheet.sh <module> <out.png> <label1> <label2> <label3>
set -e
cd "$(dirname "$0")"
python3 "$1.py" && python3 sheet.py "$1"
names=$(python3 -c "import $1; print(' '.join($1.CONCEPTS))")
cd cells
magick -size 150x150 xc:none -fill none -stroke '#999' -strokewidth 1.5 -draw "stroke-dasharray 6 6 roundrectangle 1,1 148,148 45,45" -stroke none -fill '#666' -font DejaVu-Sans -pointsize 13 -gravity center -annotate +0-8 'sin capa' -annotate +0+12 'monochrome' empty.png
cp empty.png actual-2.png; cp empty.png actual-3.png
rows=""
for n in actual $names; do
  magick -size 150x150 xc:none \( $n-4.png \) -gravity center -composite $n-4b.png
  magick $n-0.png $n-1.png $n-2.png $n-3.png $n-4b.png -background none -splice 40x0 +append row-$n.png
  rows="$rows row-$n.png"
done
magick $rows -background none -splice 0x40 -append +repage -background '#ECEAF0' -gravity southeast -extent 1180x800 -gravity northwest -font DejaVu-Sans -pointsize 18 -fill '#333' \
 -annotate +285+15 'Squircle' -annotate +475+15 'Circle' -annotate +630+15 'Themed light' -annotate +815+15 'Themed dark' -annotate +1045+15 '48 dp' \
 -font DejaVu-Sans-Bold -fill '#222' -annotate +20+145 'Actual' -annotate +20+335 "$3" -annotate +20+525 "$4" -annotate +20+715 "$5" \
 -flatten "../$2"
cd .. && rm -rf cells
