import math
from pathlib import Path
from PIL import Image, ImageDraw

def create_master_icon(size=512, is_foreground_only=False):
    # If foreground only for adaptive icon, transparent background
    if is_foreground_only:
        img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    else:
        # Midnight navy gradient background
        img = Image.new('RGBA', (size, size), (11, 19, 43, 255))
        draw_bg = ImageDraw.Draw(img)
        for y in range(size):
            r = int(11 + (28 - 11) * (y / size))
            g = int(19 + (37 - 19) * (y / size))
            b = int(43 + (65 - 43) * (y / size))
            draw_bg.line([(0, y), (size, y)], fill=(r, g, b, 255))

    draw = ImageDraw.Draw(img)

    # In adaptive foreground, content should occupy ~45-50% of the canvas (center safe zone)
    # Safe zone radius is roughly size * 0.33 (circle diameter size * 0.66)
    scale = 0.52 if is_foreground_only else 0.78
    w = int(size * scale)
    h = int(size * scale * 1.15)
    left = (size - w) // 2
    top = (size - h) // 2 - int(size * 0.02)
    right = left + w
    bottom = top + h

    # Outer Document / Vault Card
    doc_r = int(size * 0.05)
    fold = int(w * 0.28)

    # Document shape points (with top-right fold)
    doc_poly = [
        (left, top + doc_r),
        (left + doc_r, top),
        (right - fold, top),
        (right, top + fold),
        (right, bottom - doc_r),
        (right - doc_r, bottom),
        (left + doc_r, bottom),
        (left, bottom - doc_r)
    ]

    # Drop shadow if not foreground-only
    if not is_foreground_only:
        shadow_poly = [(x + 8, y + 12) for (x, y) in doc_poly]
        draw.polygon(shadow_poly, fill=(5, 8, 18, 160))

    # Base card color: Crisp premium slate/platinum gradient
    draw.polygon(doc_poly, fill=(240, 244, 248, 255))

    # Top right fold triangle
    fold_poly = [
        (right - fold, top),
        (right, top + fold),
        (right - fold, top + fold)
    ]
    draw.polygon(fold_poly, fill=(203, 213, 225, 255))
    draw.line([(right - fold, top), (right - fold, top + fold), (right, top + fold)], fill=(148, 163, 184, 255), width=int(size * 0.008))

    # Document inner border
    draw.polygon(doc_poly, outline=(148, 163, 184, 255), width=int(size * 0.012))

    # Header bar on document: Primary blue
    header_h = int(h * 0.16)
    draw.rectangle([left + int(size * 0.02), top + int(size * 0.02), right - int(size * 0.02), top + header_h], fill=(30, 58, 138, 255))

    # Center Vault Lock Emblem
    cx = size // 2
    cy = (top + header_h + bottom) // 2 - int(size * 0.01)
    radius_outer = int(w * 0.28)

    # Outer vault ring (Metallic Amber / Gold)
    draw.ellipse([cx - radius_outer, cy - radius_outer, cx + radius_outer, cy + radius_outer], fill=(245, 158, 11, 255), outline=(217, 119, 6, 255), width=int(size * 0.015))
    
    # Inner vault circle (Deep Navy)
    radius_inner = int(radius_outer * 0.72)
    draw.ellipse([cx - radius_inner, cy - radius_inner, cx + radius_inner, cy + radius_inner], fill=(15, 23, 42, 255), outline=(251, 191, 36, 255), width=int(size * 0.01))

    # Vault wheel handles / spokes
    spoke_w = int(size * 0.016)
    for angle in [0, 45, 90, 135]:
        rad = math.radians(angle)
        dx = int(radius_outer * 1.08 * math.cos(rad))
        dy = int(radius_outer * 1.08 * math.sin(rad))
        draw.line([(cx - dx, cy - dy), (cx + dx, cy + dy)], fill=(251, 191, 36, 255), width=spoke_w)

    # Central keyhole / jewel
    radius_core = int(radius_inner * 0.38)
    draw.ellipse([cx - radius_core, cy - radius_core, cx + radius_core, cy + radius_core], fill=(59, 130, 246, 255), outline=(255, 255, 255, 255), width=int(size * 0.01))

    return img

def main():
    res_dir = Path(__file__).resolve().parent / "app" / "src" / "main" / "res"
    
    # Save master preview
    master = create_master_icon(512, is_foreground_only=False)
    # The master preview is not written to a machine-specific location.

    # Densities for standard launcher icons
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192
    }

    # Adaptive icon densities (108dp base canvas: 1x=108, 1.5x=162, 2x=216, 3x=324, 4x=432)
    v26_densities = {
        "mipmap-mdpi-v26": 108,
        "mipmap-hdpi-v26": 162,
        "mipmap-xhdpi-v26": 216,
        "mipmap-xxhdpi-v26": 324,
        "mipmap-xxxhdpi-v26": 432
    }

    # Generate standard launcher icons
    for folder, px in densities.items():
        out_folder = res_dir / folder
        out_folder.mkdir(parents=True, exist_ok=True)
        icon = master.resize((px, px), Image.Resampling.LANCZOS)
        icon.save(out_folder / "ic_launcher.png")

    # Generate v26 adaptive icons
    for folder, px in v26_densities.items():
        out_folder = res_dir / folder
        out_folder.mkdir(parents=True, exist_ok=True)
        
        # Foreground (transparent with scaled graphic)
        fg = create_master_icon(px, is_foreground_only=True)
        fg.save(out_folder / "ic_launcher_foreground.png")
        
        # Background (dark midnight navy)
        bg = Image.new('RGB', (px, px), (11, 19, 43))
        bg.save(out_folder / "ic_launcher_background.png")
        
        # Monochrome (for Android 13+ themed icons)
        mono = fg.convert('LA')
        mono.save(out_folder / "ic_launcher_monochrome.png")

        # Adaptive icon XML
        xml_content = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""
        (out_folder / "ic_launcher.xml").write_text(xml_content)

    print("All mipmap icon assets generated successfully!")

if __name__ == "__main__":
    main()
