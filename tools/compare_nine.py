"""Create review sheets from the user's numbered originals and a Ycscite report (requires Pillow)."""
from argparse import ArgumentParser
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def numbered(folder, number):
    paths = sorted(p for p in folder.glob("*.png") if p.name[:2] == f"{number:02}")
    if len(paths) != 1:
        raise ValueError(f"{folder}：图 {number:02} 应恰好有一张 PNG，实际 {len(paths)} 张")
    return paths[0]


def main():
    parser = ArgumentParser(description="将九张用户原图和九张 Ycscite 图逐张并排")
    parser.add_argument("originals", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--font", default="C:/Windows/Fonts/msyh.ttc", help="中文字体文件")
    parser.add_argument("--previous", type=Path, help="额外比较旧版；按此目录中的 PNG 编号逐张出图")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype(args.font, 20)
    if args.previous is not None:
        for i in range(1, 10):
            if not any(args.previous.glob(f"{i:02}-*.png")):
                continue
            page = Image.new("RGB", (1800, 412), "#ecf0f3")
            for j, (folder, label) in enumerate(((args.originals, "原图"), (args.previous, "上一版"), (args.report, "本版"))):
                panel(page, folder, i, j * 600, 0, 600, 366, label, font)
            page.save(args.out / f"network-review-{i:02}.jpg", quality=95)
        return
    for start in (1, 4, 7):
        page = Image.new("RGB", (1440, 1320), "#ecf0f3")
        for i in range(start, start + 3):
            for j, (folder, label) in enumerate(((args.originals, "用户原图"), (args.report, "Ycscite 九图"))):
                panel(page, folder, i, j * 720, (i - start) * 440, 720, 400, label, font)
        page.save(args.out / f"原图对照-{start:02}-{start + 2:02}.jpg", quality=95)


def panel(page, folder, number, x, y, width, height, label, font):
    ImageDraw.Draw(page).text((x + 8, y + 4), f"{number:02} · {label}", font=font, fill="#17202a")
    with Image.open(numbered(folder, number)) as source:
        im = source.convert("RGBA")
        im.thumbnail((width - 10, height), Image.Resampling.LANCZOS)
        page.paste(im, (x + (width - im.width) // 2, y + 33), im)


if __name__ == "__main__":
    main()
