import json
import os
import glob
from PIL import Image, ImageDraw

def process_file(json_path):
    base_name = os.path.basename(json_path).replace("_OCR.json", "")
    dir_name = os.path.dirname(json_path)
    
    img_path = os.path.join(dir_name, base_name + ".png")
    clean_path = os.path.join(dir_name, base_name + "_clean.png")
    
    if os.path.exists(clean_path):
        img_path = clean_path
        
    if not os.path.exists(img_path):
        print(f"Image not found for {json_path}")
        return

    with open(json_path, 'r', encoding='utf-8') as f:
        raw_text = f.read().strip()
        if raw_text.startswith("```json"):
            raw_text = raw_text[7:]
        elif raw_text.startswith("```"):
            raw_text = raw_text[3:]
        if raw_text.endswith("```"):
            raw_text = raw_text[:-3]
        
        # also handle cases where there is a comment at the beginning (like the Gemini thought process)
        # We can find the first '{' and last '}'
        start_idx = raw_text.find('{')
        end_idx = raw_text.rfind('}')
        if start_idx != -1 and end_idx != -1:
            raw_text = raw_text[start_idx:end_idx+1]
            
        data = json.loads(raw_text)
        
    img = Image.open(img_path)
    width, height = img.size

    # 1. RISOLUZIONE PROBLEMA DISTORSIONE (from ocr_test.py)
    max_dim = max(width, height)
    square_img = Image.new("RGB", (max_dim, max_dim), (255, 255, 255))
    offset_x = (max_dim - width) // 2
    offset_y = (max_dim - height) // 2
    square_img.paste(img, (offset_x, offset_y))

    # We do NOT resize to 1000x1000 here because the JSON coordinates are already relative to 1000x1000.
    # ocr_test.py resizes BEFORE calling the API. The API returns coordinates in 1000x1000 space.
    
    draw = ImageDraw.Draw(img)
    balloons = data.get("balloons", [])

    for i, b in enumerate(balloons):
        balloon_box = b.get("balloon_box_2d", [0, 0, 0, 0])
        text_box = b.get("text_box_2d", [0, 0, 0, 0])

        if len(balloon_box) != 4 or len(text_box) != 4:
            continue

        by0 = (balloon_box[0] / 1000.0) * height
        bx0 = (balloon_box[1] / 1000.0) * width
        by1 = (balloon_box[2] / 1000.0) * height
        bx1 = (balloon_box[3] / 1000.0) * width

        ty0 = (text_box[0] / 1000.0) * height
        tx0 = (text_box[1] / 1000.0) * width
        ty1 = (text_box[2] / 1000.0) * height
        tx1 = (text_box[3] / 1000.0) * width

        draw.rectangle([bx0, by0, bx1, by1], outline="purple", width=2)

        cx = (tx0 + tx1) / 2
        cy = (ty0 + ty1) / 2

        bw = bx1 - bx0
        bh = by1 - by0

        tw = tx1 - tx0
        th = ty1 - ty0

        if bw >= 2 * tw or bh >= 2 * th:
            bw = tw * 1.25
            bh = th * 1.25

        ibx0 = cx - bw / 2
        ibx1 = cx + bw / 2
        iby0 = cy - bh / 2
        iby1 = cy + bh / 2

        THRESHOLD = 100

        if by0 < THRESHOLD:
            iby0 = 0
        elif height - by1 < THRESHOLD:
            iby1 = height

        draw.rectangle([ibx0, iby0, ibx1, iby1], outline="orange", width=4)

        shape = b.get("shape", "oval").lower()
        if shape == "rectangular":
            draw.rectangle([ibx0, iby0, ibx1, iby1], outline="blue", width=4)
        else:
            draw.ellipse([ibx0, iby0, ibx1, iby1], outline="blue", width=4)

        draw.rectangle([tx0, ty0, tx1, ty1], outline="green", width=3)

        draw.line([(cx - 10, cy), (cx + 10, cy)], fill="red", width=3)
        draw.line([(cx, cy - 10), (cx, cy + 10)], fill="red", width=3)

    # Save to tmp directory
    import tempfile
    out_dir = os.path.join(tempfile.gettempdir(), "ocr_test_py_outputs")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, base_name + "_python_bbs.png")
    img.save(out_path)
    print(f"Saved {out_path}")

for json_file in glob.glob("*_OCR.json"):
    process_file(json_file)
