import os
import sys
import json
from PIL import Image, ImageDraw, ImageFont
import google.generativeai as genai

def test_ocr(image_path):
    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("API_KEY")
    if not api_key:
        print("Set the GEMINI_API_KEY environment variable.")
        sys.exit(1)
        
    genai.configure(api_key=api_key)
    
    # Prompt da KoogOcrService
    prompt_instructions = (
        "Analyze this comic panel. Locate ALL areas containing text (speech bubbles, captions, and text boxes). "
        "Do NOT transcribe sound effects (SFX) or onomatopoeia that appear OUTSIDE of speech bubbles (e.g. drawn directly on the artwork). "
        "For each text area provide:\n"
        " 1. The bounding box of the TEXT ITSELF (not the balloon outline).\n"
        " Express coordinates as FRACTIONS of the image dimensions, between 0.0 and 1.0:\n"
        " xmin = left edge / image_width, ymin = top edge / image_height,\n"
        " xmax = right edge / image_width, ymax = bottom edge / image_height.\n"
        " 2. The exact text transcribed from that area."
    )
    
    # Usiamo gemini-1.5-flash come default, con response_mime_type in json
    model = genai.GenerativeModel(
        "gemini-1.5-pro",
        generation_config={
            "response_mime_type": "application/json",
            "response_schema": {
                "type": "object",
                "properties": {
                    "balloons": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "xmin": {"type": "number"},
                                "ymin": {"type": "number"},
                                "xmax": {"type": "number"},
                                "ymax": {"type": "number"},
                                "text": {"type": "string"}
                            },
                            "required": ["xmin", "ymin", "xmax", "ymax", "text"]
                        }
                    }
                },
                "required": ["balloons"]
            }
        }
    )

    print(f"Loading image: {image_path}")
    img = Image.open(image_path)
    width, height = img.size
    
    print("Calling Gemini...")
    response = model.generate_content([prompt_instructions, img])
    
    try:
        data = json.loads(response.text)
    except Exception as e:
        print("Failed to parse JSON:", e)
        print("Raw response:", response.text)
        return

    print("Drawing bounding boxes...")
    draw = ImageDraw.Draw(img)
    balloons = data.get("balloons", [])
    
    for i, b in enumerate(balloons):
        xmin = b["xmin"] * width
        ymin = b["ymin"] * height
        xmax = b["xmax"] * width
        ymax = b["ymax"] * height
        
        # Disegna il box
        draw.rectangle([xmin, ymin, xmax, ymax], outline="red", width=3)
        print(f"Balloon {i+1}: {b['text']} @ {b['xmin']:.2f}, {b['ymin']:.2f}, {b['xmax']:.2f}, {b['ymax']:.2f}")

    out_path = os.path.splitext(image_path)[0] + "_ocr_test.png"
    img.save(out_path)
    print(f"Saved result to {out_path}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python ocr_test.py <image_path>")
        sys.exit(1)
    test_ocr(sys.argv[1])
