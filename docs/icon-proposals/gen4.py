"""E6 (5 petals, light facet toward the center) across palettes."""
from gen3 import sheet, five, SPECTRUM6, JEWEL6

E6 = dict(n=5, width=19, length=24, gap=3.4, rr=1.3, split="cross", side=0.4, mono_width=15.5)
# (light facet, dark facet), clockwise from the top petal
PALETTES = {
  "Espectro (actual E6)": five(SPECTRUM6),
  "Joya profunda":        five(JEWEL6),
  "Fragua (brasa→oro)":   [("#FF6B4A", "#D9432A"), ("#FF8F3F", "#E56D1F"), ("#FFB547", "#EB9420"),
                           ("#F7CF5B", "#DDAE2E"), ("#E8765E", "#C8533E")],
  "Atardecer":            [("#FF7B6B", "#E4574A"), ("#FFA94D", "#EE8A26"), ("#FFD166", "#EFB437"),
                           ("#F277A8", "#D6568A"), ("#A77BF3", "#8759D8")],
  "Aurora":               [("#3DD6B5", "#1FB395"), ("#58C4F0", "#2FA2D2"), ("#6C7CF5", "#4C5BD8"),
                           ("#B57AF0", "#9557D4"), ("#F07AB8", "#D35898")],
  "Amber→violet ramp":  [("#FFC247", "#F0A21E"), ("#FF8A4C", "#EB6A2C"), ("#F2607A", "#D4415C"),
                           ("#C45ED8", "#A23FBA"), ("#7B61F0", "#5B43D4")],
  "Pastel suave":         [("#FF9E8A", "#F07E69"), ("#FFCB7A", "#F2B055"), ("#8FDCC2", "#66C5A6"),
                           ("#8DB4F7", "#6A94E6"), ("#C3A2F5", "#A67FE6")],
  "Vivid neon":          [("#FF4D6D", "#E0294B"), ("#FF9F1C", "#E88100"), ("#2EE6A8", "#12C48A"),
                           ("#3A86FF", "#1F66E0"), ("#B537F2", "#9219D1")],
}
VARIANTS = {f"E6 · {k}": dict(E6, colors=v) for k, v in PALETTES.items()}

if __name__ == "__main__":
    sheet(VARIANTS, "sheet-e6-palettes.png")
