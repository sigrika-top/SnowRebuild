"""Build compact shape templates from the example screenshots."""
from pathlib import Path
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(sys.argv[1])
FILES = {p.stem[:8]: p for p in SOURCE.glob("*.jpg")}
IMAGES = {}


def image(key):
    if key not in IMAGES:
        IMAGES[key] = Image.open(FILES[key]).convert("L")
    return IMAGES[key]


def components(img, box, threshold):
    x0, y0, x1, y1 = box
    unvisited = {(x, y) for x in range(x0, x1) for y in range(y0, y1)
                 if img.getpixel((x, y)) <= threshold}
    groups = []
    while unvisited:
        first = unvisited.pop()
        queue = [first]
        for x, y in queue:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    p = (x+dx, y+dy)
                    if p in unvisited:
                        unvisited.remove(p)
                        queue.append(p)
        height = max(y for x, y in queue) - min(y for x, y in queue) + 1
        if len(queue) >= 12 and height >= 9:
            groups.append(queue)
    return sorted(groups, key=lambda pixels: min(x for x, y in pixels))


def shape(img, box, threshold, light=False, width=12, height=18):
    x0, y0, x1, y1 = box
    pixels = [(x, y) for x in range(x0, x1) for y in range(y0, y1)
              if (img.getpixel((x, y)) > threshold) == light]
    return fingerprint(pixels, width, height)


def fingerprint(pixels, width=12, height=18):
    if len(pixels) < 12:
        return None
    x0, x1 = min(x for x, _ in pixels), max(x for x, _ in pixels) + 1
    y0, y1 = min(y for _, y in pixels), max(y for _, y in pixels) + 1
    pixels = set(pixels)
    return ''.join('1' if
        (x0 + int((x + .5) * (x1 - x0) / width),
         y0 + int((y + .5) * (y1 - y0) / height)) in pixels else '0'
        for y in range(height) for x in range(width))


def icon(img, cx, cy):
    return ''.join('1' if img.getpixel((cx + x * 3, cy + y * 3)) < 120 else '0'
                   for y in range(-8, 9) for x in range(-8, 9))


layouts = [
    [(1833,525),(1632,701),(1350,622),(1180,682),(993,590),(773,507),(955,408),(1166,422),(1406,326),(1633,427)],
    [(1836,521),(1623,688),(1384,575),(1152,688),(928,640),(774,502),(956,395),(1197,356),(1506,292),(1681,394)],
    [(1815,510),(1603,668),(1315,668),(1070,605),(809,550),(950,394),(1107,437),(1300,359),(1500,418),(1658,319)],
    [(1817,515),(1670,648),(1434,620),(1168,607),(934,618),(806,425),(1003,447),(1236,409),(1446,342),(1624,455)],
    [(1794,553),(1508,710),(1358,566),(1147,674),(944,665),(775,516),(978,335),(1199,350),(1444,345),(1653,366)],
    [(1775,409),(1688,632),(1510,694),(1399,522),(1171,579),(951,660),(783,484),(977,355),(1192,358),(1499,369)],
    [(1775,519),(1648,675),(1454,689),(1199,650),(926,685),(789,445),(1020,481),(1181,366),(1413,384),(1646,333)],
    [(1779,516),(1643,658),(1453,565),(1248,608),(942,688),(798,428),(1020,450),(1180,365),(1395,360),(1642,380)],
]

map_samples = [
    ("414ecbf2", 0, "0120201201"),
    ("67180163", 1, "0120201201"),
    ("57b6bb2f", 1, "0120221010"),
    ("30b1f311", 2, "0120201201"),
    ("8ce81344", 3, "0120201201"),
    ("7c3b3880", 4, "0430430433"),
    ("77a6cb4b", 5, "0430430433"),
    ("25c8391b", 6, "0430430433"),
    ("aeb872c5", 6, "4003044333"),
]

# Top, middle and bottom rows, displayed from left to right.
plan_samples = {
    "414ecbf2": ("0/37 0/37", "0/10 0/10", "0/12 0/8"),
    "1f02865b": ("6/37 20/37", "2/10 6/10", "2/12 6/8"),
    "2b4192d1": ("18/37 22/37", "0/10 0/10", "0/18"),
    "f532c430": ("0/50 0/25", "10/10 2/10", "2/18"),
    "86c510f0": ("22/50 2/25", "10/10 2/10", "0/12 0/8"),
    "67180163": ("0/40 0/20", "0/8 0/8", "0/10 0/6"),
    "57b6bb2f": ("16/40 2/20", "2/8 8/8", "0/8 0/8"),
    "420771d0": ("18/40 20/20", "2/15", "10/10 2/6"),
    "3f9c89df": ("28/40 2/20", "0/8 0/8", "0/10 6/6"),
    "aeb872c5": ("96/100 71/100", "4/25 25/25", "30/30 9/20"),
    "77a6cb4b": ("0/80 0/40", "0/15 0/15", "0/18 0/12"),
    "ac5c8000": ("0/80 15/40", "0/15 15/15", "15/18 12/12"),
    "25c8391b": ("0/100 0/100", "0/30 0/20", "0/30 0/20"),
    "ea9589b2": ("9/40 0/20", "0/15", "0/10 0/6"),
    "976aa1ac": ("40/60 4/60", "2/25", "6/18 12/12"),
}

output = []
for layout in layouts:
    output.append("L " + " ".join(f"{x} {y}" for x, y in layout))

# Map and plan icons have different silhouettes against their backgrounds.
for material, key, x, y in [
    (0,"414ecbf2", 2223,305), (1,"414ecbf2",2416,305),
    (2,"414ecbf2",2414,178), (3,"7c3b3880",2223,305),
    (4,"7c3b3880",2414,178), (5,"57b6bb2f",2416,305),
]:
    output.append(f"P {material} {icon(image(key), x, y)}")

# Use clean examples with sufficient contrast for each symbol.
for material, key, x, y in [
    (0,"414ecbf2", 773,507), (0,"414ecbf2",1406,326),
    (1,"414ecbf2",955,408), (1,"414ecbf2",1632,701),
    (2,"414ecbf2",1166,422), (2,"414ecbf2",1350,622),
    (3,"7c3b3880",775,516), (3,"7c3b3880",1444,345),
    (4,"7c3b3880",944,665), (4,"7c3b3880",1508,710),
    (4,"aeb872c5",1020,481),
]:
    output.append(f"M {material} {icon(image(key), x, y)}")

for key, cards in {
    "414ecbf2": "22445", "8ce81344": "11133", "976aa1ac": "11334455",
    "9fe5001f": "111233", "aeb872c5": "123445", "f532c430": "14455",
}.items():
    for i, digit in enumerate(cards):
        x = 144 + 167 * i
        mark = shape(image(key), (x-26, 1001, x+27, 1081), 179, True)
        if mark:
            output.append(f"C {digit} {mark}")

for key, digit, x, y in [
    ("414ecbf2",2,1833,578), ("7c3b3880",3,1790,603),
    ("88c5736e",5,1182,433), ("0a91882d",0,950,446),
    ("cbde5a9a",4,1660,373), ("67180163",2,1837,576),
    ("09796899",7,1396,410),
]:
    mark = shape(image(key), (x-18, y-16, x+19, y+16), 115)
    if mark:
        output.append(f"Y {digit} {mark}")

for key, rows in plan_samples.items():
    for row, values in enumerate(rows):
        tokens = values.split()
        cy = [168, 296, 422][row]
        for slot, value in enumerate(tokens, 2-len(tokens)):
            x = [2264, 2454][slot]
            img = image(key)
            box = (x, cy-15, x+105, cy+14)
            for threshold in (143, 156, 168, 180):
                groups = components(img, box, threshold)
                if len(groups) != len(value):
                    continue
                for glyph, group in zip(value, groups):
                    mark = fingerprint(group)
                    if mark:
                        output.append(f"D {glyph} {mark}")

(ROOT / "assets" / "shapes.txt").write_text("\n".join(dict.fromkeys(output))+"\n", encoding="ascii")
