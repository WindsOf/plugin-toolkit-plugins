from google import genai
import PIL.Image
from dotenv import load_dotenv
import os
import re
import argparse
from pathlib import Path
from tenacity import retry, stop_after_attempt, wait_exponential

load_dotenv()

client = None


@retry(
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=60, min=60, max=120),
    before_sleep=lambda retry_state: print(
        f"Error detected. Attempt {retry_state.attempt_number} failed. Next attempt in {retry_state.next_action.sleep} seconds..."
    ),
)
def run_ocr_cloud(image_path):
    img = PIL.Image.open(image_path)

    prompt = (
        "Transcribe only the text contained in the image, maintaining the original layout. "
        "DO NOT add introductions, notes, comments, or task analysis. "
        "Return ONLY the extracted text."
    )

    if client is None:
        raise Exception("GenAI client is not initialized.")

    response = client.models.generate_content(
        model="gemma-4-31b-it", contents=[prompt, img]
    )

    if not response or not response.text:
        raise Exception("The model returned an empty or null response.")

    text = response.text
    text = re.sub(
        r"<(thought|thinking)>.*?</\1>", "", text, flags=re.DOTALL | re.IGNORECASE
    )
    return text.strip()


def process_image(image_path, save_to_file=False, output_dir=None):
    print(f"Processing: {image_path}...")
    try:
        extracted_text = run_ocr_cloud(image_path)

        if save_to_file:
            if output_dir:
                output_path = Path(output_dir) / f"{image_path.stem}_OCR.txt"
            else:
                output_path = image_path.with_name(f"{image_path.stem}_OCR.txt")

            with open(output_path, "w", encoding="utf-8") as f:
                f.write(extracted_text)
            print(f"Saved to: {output_path}")
        else:
            print("-" * 20)
            print(extracted_text)
            print("-" * 20)
    except Exception as e:
        print(f"Error during processing of {image_path}: {e}")


def main():
    parser = argparse.ArgumentParser(description="OCR Tool using Google GenAI")
    parser.add_argument("input", help="Path to an image or a folder containing images")
    parser.add_argument(
        "-s",
        "--save",
        action="store_true",
        help="Save the output to a text file (.txt) instead of printing it",
    )
    parser.add_argument(
        "-o",
        "--output-dir",
        help="Destination folder for saved files (optional, default: same folder as the image)",
    )
    parser.add_argument(
        "-k",
        "--api-key",
        help="Google GenAI API Key (optional, will use .env or environment variable if not provided)",
    )

    args = parser.parse_args()
    input_path = Path(args.input)
    output_dir = Path(args.output_dir) if args.output_dir else None

    api_key = args.api_key or os.getenv("API_KEY")
    if not api_key:
        print(
            "Error: API Key not found. Provide it via --api-key or set the API_KEY environment variable (e.g., in a .env file)."
        )
        return

    global client
    client = genai.Client(api_key=api_key)

    if not input_path.exists():
        print(f"Error: Path '{input_path}' does not exist.")
        return

    if output_dir and not output_dir.exists():
        print(f"Creating output folder: {output_dir}")
        output_dir.mkdir(parents=True, exist_ok=True)

    image_extensions = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}

    if input_path.is_file():
        if input_path.suffix.lower() in image_extensions:
            process_image(input_path, args.save, output_dir)
        else:
            print(f"Error: File '{input_path}' is not a supported image.")
    elif input_path.is_dir():
        files = sorted(
            [f for f in input_path.iterdir() if f.suffix.lower() in image_extensions]
        )
        if not files:
            print(f"No images found in folder '{input_path}'.")
            return

        print(f"Found {len(files)} images. Starting processing...")
        for file in files:
            process_image(file, args.save, output_dir)
    else:
        print("Error: Invalid path.")


if __name__ == "__main__":
    main()
