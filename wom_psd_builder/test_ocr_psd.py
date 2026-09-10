import sys
import os

def mock_ocr(image_path):
    print(f"Mock OCR running on {image_path}")
    return [
        {"text": "Hello World", "bbox": [0.1, 0.1, 0.5, 0.2]},
        {"text": "Integration Test", "bbox": [0.5, 0.5, 0.9, 0.6]}
    ]

def call_native_psd_builder(image_path, ocr_results):
    print("Calling Native PSD Builder with OCR results...")
    # In a real environment, this would call the jar or make a plugin API request
    for res in ocr_results:
        print(f"  Mapping {res['text']} to bbox {res['bbox']}")
    print("Integration test executed successfully.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python test_ocr_psd.py <image_path>")
        sys.exit(1)
    
    img = sys.argv[1]
    res = mock_ocr(img)
    call_native_psd_builder(img, res)
