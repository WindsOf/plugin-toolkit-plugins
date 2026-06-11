import os
import sys
import json
import math
from dotenv import load_dotenv
from PIL import Image, ImageDraw, ImageFont
from google import genai
from google.genai import types
from pydantic import BaseModel


# --- LOGICA DI IMPAGINAZIONE A DIAMANTE (ELLISSE) ---
def get_ellipse_width(y_offset, rx, ry):
    """Calcola la larghezza disponibile in un'ellisse a una certa altezza Y dal centro."""
    if abs(y_offset) >= ry:
        return 0
    return 2 * rx * math.sqrt(1 - (y_offset / ry) ** 2)


def wrap_text_diamond(text, font, rx, ry, cy_offset_from_ellipse=0):
    """Spezza il testo in righe calcolando la larghezza curva dell'ellisse, supportando un centro del testo disassato."""
    words = text.split()
    line_height = font.size * 1.2

    # Numero massimo stimato di righe che possono entrare nell'ellisse utile
    max_possible_lines = int((2 * ry) / line_height)

    for num_lines in range(1, max_possible_lines + 1):
        lines = []
        word_idx = 0

        for i in range(num_lines):
            if word_idx >= len(words):
                break

            # Posizione della riga rispetto al centro del TESTO
            line_y_rel_to_text = (i - (num_lines - 1) / 2.0) * line_height

            # Posizione della riga rispetto al centro dell'ELLISSE
            y_offset = line_y_rel_to_text + cy_offset_from_ellipse

            y_top = y_offset - (line_height / 2.0)
            y_bottom = y_offset + (line_height / 2.0)
            worst_y = max(abs(y_top), abs(y_bottom))

            if worst_y >= ry:
                max_w = 0
            else:
                max_w = get_ellipse_width(worst_y, rx, ry)

            current_line = []
            current_w = 0

            while word_idx < len(words):
                word = words[word_idx]
                w = font.getlength(word + " ")
                if current_w + w <= max_w or len(current_line) == 0:
                    current_line.append(word)
                    current_w += w
                    word_idx += 1
                else:
                    break

            if max_w == 0 and len(current_line) > 0:
                word_idx = 0
                break

            lines.append(" ".join(current_line))

        if word_idx >= len(words):
            return lines

    return None


def wrap_text_rectangle(text, font, max_w, max_h, cy_offset_from_box=0):
    """Spezza il testo in righe basandosi su una larghezza costante rettangolare, controllando i limiti verticali."""
    words = text.split()
    line_height = font.size * 1.2
    max_possible_lines = int(max_h / line_height)

    for num_lines in range(1, max_possible_lines + 1):
        lines = []
        word_idx = 0
        for i in range(num_lines):
            if word_idx >= len(words):
                break

            line_y_rel_to_text = (i - (num_lines - 1) / 2.0) * line_height
            y_offset = line_y_rel_to_text + cy_offset_from_box
            y_top = y_offset - (line_height / 2.0)
            y_bottom = y_offset + (line_height / 2.0)
            worst_y = max(abs(y_top), abs(y_bottom))

            # Se la riga esce dal rettangolo in verticale
            if worst_y >= max_h / 2:
                word_idx = 0
                break

            current_line = []
            current_w = 0
            while word_idx < len(words):
                word = words[word_idx]
                w = font.getlength(word + " ")
                if current_w + w <= max_w or len(current_line) == 0:
                    current_line.append(word)
                    current_w += w
                    word_idx += 1
                else:
                    break

            lines.append(" ".join(current_line))

        if word_idx >= len(words):
            return lines
    return None


def draw_emulated_text(draw, text, bx0, by0, bx1, by1, cx, cy, shape="oval"):
    """Trova la grandezza giusta del font e disegna il testo seguendo la forma specificata, rispettando il vero centro del testo cx, cy."""
    ellipse_cy = (by0 + by1) / 2
    cy_offset_from_ellipse = cy - ellipse_cy

    # Applichiamo un margine del 5% su larghezza e altezza
    bw = (bx1 - bx0) * 0.95
    bh = (by1 - by0) * 0.95
    rx = bw / 2
    ry = bh / 2

    best_lines = None
    best_font = None

    for size in range(60, 8, -1):
        try:
            font = ImageFont.truetype("animeace2_reg.ttf", size)
        except:
            font = ImageFont.load_default()

        if shape.lower() == "rectangular":
            lines = wrap_text_rectangle(text, font, bw, bh, cy_offset_from_ellipse)
        else:
            lines = wrap_text_diamond(text, font, rx, ry, cy_offset_from_ellipse)

        if lines:
            safe_size = max(8, size - 8)
            try:
                best_font = ImageFont.truetype("animeace2_reg.ttf", safe_size)
            except:
                best_font = ImageFont.load_default()

            if shape.lower() == "rectangular":
                best_lines = wrap_text_rectangle(
                    text, best_font, bw, bh, cy_offset_from_ellipse
                )
            else:
                best_lines = wrap_text_diamond(
                    text, best_font, rx, ry, cy_offset_from_ellipse
                )
            break

    if best_lines and best_font:
        line_height = best_font.size * 1.2
        num_lines = len(best_lines)
        for i, line in enumerate(best_lines):
            y_offset = (i - (num_lines - 1) / 2.0) * line_height
            draw.text(
                (cx, cy + y_offset), line, fill="blue", font=best_font, anchor="mm"
            )


class Balloon(BaseModel):
    balloon_box_2d: list[float]
    text_box_2d: list[float]
    shape: str
    text: str


class ResponseFormat(BaseModel):
    balloons: list[Balloon]


def test_ocr(image_path):
    # Carica le variabili d'ambiente dal file .env
    load_dotenv()

    api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("API_KEY")

    if not api_key:
        print(
            "Errore: API key mancante! Assicurati di avere GEMINI_API_KEY o API_KEY nel file .env"
        )
        sys.exit(1)

    client = genai.Client(api_key=api_key)

    print(f"Loading image: {image_path}")
    img = Image.open(image_path)
    width, height = img.size

    # 1. RISOLUZIONE PROBLEMA DISTORSIONE:
    # Invece di "schiacciare" l'immagine perdendo l'aspect ratio originale (che causava l'errore asimmetrico XY),
    # creiamo un quadrato perfetto aggiungendo bordi bianchi (letterboxing), e POI scaliamo a 1000x1000.
    max_dim = max(width, height)
    square_img = Image.new("RGB", (max_dim, max_dim), (255, 255, 255))
    offset_x = (max_dim - width) // 2
    offset_y = (max_dim - height) // 2
    square_img.paste(img, (offset_x, offset_y))

    small_img = square_img.resize((1000, 1000), Image.Resampling.LANCZOS)

    # Prompt aggiornato per richiedere entrambe le bounding box
    prompt_instructions = (
        "Analyze this comic panel. Locate ALL areas containing text (speech bubbles, captions, and text boxes). "
        "Do NOT transcribe sound effects (SFX) or onomatopoeia that appear OUTSIDE of speech bubbles (e.g. drawn directly on the artwork). "
        "For each text area provide:\n"
        " 1. The bounding box of the SPEECH BUBBLE / BALLOON enclosing the text.\n"
        " CRITICAL: EXCLUDE the 'tail' or 'pointer' of the balloon. The bounding box MUST strictly wrap only the main body.\n"
        " 2. The bounding box of the TEXT ITSELF (the tightest box around the transcribed words).\n"
        " Express coordinates as EXACT ABSOLUTE PIXELS based on the image dimensions (width: 1000px, height: 1000px).\n"
        " Provide a 'balloon_box_2d' array and a 'text_box_2d' array containing exactly 4 integers in this STRICT ORDER: [ymin, xmin, ymax, xmax].\n"
        " 3. The 'shape' of the bubble. Choose EXACTLY ONE from: 'oval' or 'rectangular'. If it's a standard comic bubble, choose 'oval'. If it's a caption box, a square panel or an irregular balloon, choose 'rectangular'.\n"
        " 4. The exact text transcribed from that area."
    )

    print("Calling model (gemma-4-26b-a4b-it) with temperature=0.0...")
    try:
        response = client.models.generate_content(
            model="gemma-4-26b-a4b-it",
            contents=[small_img, prompt_instructions],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=ResponseFormat,
                temperature=0.0,
            ),
        )
    except Exception as e:
        print(f"API Error: {e}")
        return

    try:
        raw_text = response.text.strip()
        if raw_text.startswith("```json"):
            raw_text = raw_text[7:]
        elif raw_text.startswith("```"):
            raw_text = raw_text[3:]
        if raw_text.endswith("```"):
            raw_text = raw_text[:-3]

        data = json.loads(raw_text.strip())
    except Exception as e:
        print("Failed to parse JSON:", e)
        print("Raw response:", response.text)
        return

    print("Drawing bounding boxes...")
    draw = ImageDraw.Draw(img)
    balloons = data.get("balloons", [])

    for i, b in enumerate(balloons):
        balloon_box = b.get("balloon_box_2d", [0, 0, 0, 0])
        text_box = b.get("text_box_2d", [0, 0, 0, 0])

        if len(balloon_box) != 4 or len(text_box) != 4:
            continue

        # 1. Calcoliamo la scala rispetto al QUADRATO (max_dim)
        scale = max_dim / 1000.0

        # 2. Rimuoviamo l'offset che avevamo aggiunto per il letterboxing
        # In questo modo i pixel tornano a essere relativi all'immagine originale non quadrata!
        b_ymin, b_xmin, b_ymax, b_xmax = balloon_box
        bx0, bx1 = sorted([(b_xmin * scale) - offset_x, (b_xmax * scale) - offset_x])
        by0, by1 = sorted([(b_ymin * scale) - offset_y, (b_ymax * scale) - offset_y])

        # Calcolo per Testo (Rettangolo)
        t_ymin, t_xmin, t_ymax, t_xmax = text_box
        tx0, tx1 = sorted([(t_xmin * scale) - offset_x, (t_xmax * scale) - offset_x])
        ty0, ty1 = sorted([(t_ymin * scale) - offset_y, (t_ymax * scale) - offset_y])

        # Disegna il box rettangolare nativo del balloon (viola)
        draw.rectangle([bx0, by0, bx1, by1], outline="purple", width=2)

        # --- LOGICA DI INTERPOLAZIONE (IL TERZO RETTANGOLO) ---
        # 1. Calcoliamo il centro esatto basandoci sul TESTO (che è precisissimo)
        cx = (tx0 + tx1) / 2
        cy = (ty0 + ty1) / 2

        # 2. Calcoliamo la larghezza e altezza del BALLOON stimato dall'IA
        bw = bx1 - bx0
        bh = by1 - by0

        tw = tx1 - tx0
        th = ty1 - ty0

        # --- FAILSAFE ANOMALIE IA ---
        # Se la Bounding Box del balloon è enorme su UN asse qualsiasi (> 2x la grandezza del testo),
        # significa che l'IA ha preso pezzi di disegno sbagliati o ha inglobato personaggi.
        # Ignoriamo COMPLETAMENTE la stima dell'IA su entrambi gli assi e calcoliamo una BB espandendo il testo del 25%.
        if bw >= 2 * tw or bh >= 2 * th:
            bw = tw * 1.25
            bh = th * 1.25

        # 3. Creiamo la Bounding Box Interpolata: prendiamo le dimensioni del balloon (o corrette),
        # ma le centriamo perfettamente attorno al centro del testo!
        # Questo elimina l'errore di shift (lo scarto tra le distanze superiori e inferiori)
        ibx0 = cx - bw / 2
        ibx1 = cx + bw / 2
        iby0 = cy - bh / 2
        iby1 = cy + bh / 2

        # --- OVER FIX: ESPANSIONE AI BORDI ---
        # Se il balloon originale dell'IA è a meno di 100 pixel dai bordi,
        # l'IA tende a "schiacciarlo". Forziamo la BB interpolata a toccare il bordo
        # e per mantenere la perfezione matematica (il centro), espandiamo specularmente
        # anche il lato opposto!
        THRESHOLD = 100

        # Bordo Superiore o Inferiore
        if by0 < THRESHOLD:
            iby0 = 0
        elif height - by1 < THRESHOLD:
            iby1 = height

        # Disegna il Terzo Rettangolo (Arancione)
        draw.rectangle([ibx0, iby0, ibx1, iby1], outline="orange", width=4)

        # Disegna l'ellisse o il rettangolo blu a seconda della forma rilevata!
        shape = b.get("shape", "oval").lower()
        if shape == "rectangular":
            draw.rectangle([ibx0, iby0, ibx1, iby1], outline="blue", width=4)
        else:
            draw.ellipse([ibx0, iby0, ibx1, iby1], outline="blue", width=4)

        # Disegna il box verde per il testo effettivo
        draw.rectangle([tx0, ty0, tx1, ty1], outline="green", width=3)

        # Disegna un mirino/croce esatto al centro della text box per indicare l'ancoraggio!
        cx = (tx0 + tx1) / 2
        cy = (ty0 + ty1) / 2
        draw.line([(cx - 10, cy), (cx + 10, cy)], fill="red", width=3)
        draw.line([(cx, cy - 10), (cx, cy + 10)], fill="red", width=3)

        # Emula l'inserimento del testo nel balloon usando l'equazione dell'ellisse o del rettangolo
        # (Passiamo la nuova Bounding Box INTERPOLATA, il centro inviolabile cx/cy, e la forma)
        draw_emulated_text(draw, b["text"], ibx0, iby0, ibx1, iby1, cx, cy, shape)

        print(
            f"Balloon {i + 1}: {b['text']}\n"
            f"  -> Shape: {shape.upper()}\n"
            f"  -> Balloon Original: {bx0:.1f}, {by0:.1f}, {bx1:.1f}, {by1:.1f}\n"
            f"  -> Interpolated BB: {ibx0:.1f}, {iby0:.1f}, {ibx1:.1f}, {iby1:.1f}\n"
            f"  -> Text Center: {cx:.1f}, {cy:.1f}"
        )

    out_path = os.path.splitext(image_path)[0] + "_ocr_test2.png"
    img.save(out_path)
    print(f"Saved result to {out_path}")


if __name__ == "__main__":
    # Inserisci qui il percorso della tua immagine di test
    # IMAGE_PATH = r"G:\WOM\TBATE\CH233\test\42.png"
    IMAGE_PATH = r"G:\WOM\TBATE\CH233\test\34.png"

    if not os.path.exists(IMAGE_PATH):
        print(
            f"Errore: l'immagine {IMAGE_PATH} non esiste. Modifica la variabile IMAGE_PATH nello script."
        )
        sys.exit(1)

    test_ocr(IMAGE_PATH)
