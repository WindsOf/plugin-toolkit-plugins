const fs = require('fs');
const { writePsdBuffer } = require('ag-psd');
const { Jimp } = require('jimp');



async function generatePSD(jsonPath, outputPath) {
    const payload = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));

    if (!payload.backgroundImage) {
        throw new Error("Missing 'backgroundImage' in JSON payload.");
    }

    const bgImage = await Jimp.read(payload.backgroundImage);

    const canvasData = {
        width: bgImage.bitmap.width,
        height: bgImage.bitmap.height,
        data: new Uint8ClampedArray(bgImage.bitmap.data)
    };

    const childrenNodes = [
        {
            name: 'background',
            imageData: canvasData,
            left: 0, top: 0, right: canvasData.width, bottom: canvasData.height
        },
        {
            name: 'clean',
            imageData: canvasData,
            left: 0, top: 0, right: canvasData.width, bottom: canvasData.height
        }
    ];

    const textsList = payload.texts || [];

    function calculateOptimalTextProperties(text, originalW, originalH) {
        if (!text || text.trim() === "") {
            return {
                fontSize: 24,
                newW: originalW,
                newH: originalH,
                textHeight: 24 * 1.15,
                offsetY: 0
            };
        }

        const aspectRatio = 0.85; // Stima larghezza media carattere per font molto larghi (es. Anime Ace)
        const lineHeightRatio = 1.15; // Stima altezza riga
        const maxScale = 1.05; // Ingrandimento massimo consentito (5%)

        let W_max = originalW * maxScale;
        let H_max = originalH * maxScale;

        let optimalS = 10;
        let finalW = originalW;
        let finalH = originalH;
        let finalTextHeight = originalH;

        for (let S = 100; S >= 18; S--) {
            let cw = S * aspectRatio;
            let lh = S * lineHeightRatio;

            let linesCount = 0;
            let paragraphs = text.trim().split('\n');
            let longestLine = 0;

            for (let p of paragraphs) {
                let words = p.trim().split(/\s+/);
                if (words.length === 0 || (words.length === 1 && words[0] === "")) {
                    linesCount++;
                    continue;
                }
                let currentLineWidth = 0;
                for (let i = 0; i < words.length; i++) {
                    let w = words[i];
                    let wordWidth = w.length * cw;

                    if (currentLineWidth === 0) {
                        currentLineWidth = wordWidth;
                    } else if (currentLineWidth + cw + wordWidth <= W_max) {
                        currentLineWidth += cw + wordWidth;
                    } else {
                        linesCount++;
                        if (currentLineWidth > longestLine) longestLine = currentLineWidth;
                        currentLineWidth = wordWidth;
                    }
                }
                if (currentLineWidth > 0) {
                    linesCount++;
                    if (currentLineWidth > longestLine) longestLine = currentLineWidth;
                }
            }

            let totalH = linesCount * lh;

            if (totalH <= H_max && longestLine <= W_max) {
                optimalS = S;
                finalTextHeight = totalH;

                if (totalH > originalH || longestLine > originalW) {
                    finalW = W_max;
                    finalH = H_max;
                } else {
                    finalW = originalW;
                    finalH = originalH;
                }
                break;
            }
        }

        let offsetY = (finalH - finalTextHeight) / 2;
        if (offsetY < 0) offsetY = 0;

        return {
            fontSize: optimalS,
            newW: finalW,
            newH: finalH,
            textHeight: finalTextHeight,
            offsetY: offsetY
        };
    }

    textsList.forEach((txtConfig, index) => {
        const textContent = txtConfig.text || "";
        let fontName = txtConfig.fontName || 'AnimeAce2.0BB';

        const left = txtConfig.left;
        const top = txtConfig.top;
        const right = txtConfig.right;
        const bottom = txtConfig.bottom;

        if (left === undefined || top === undefined || right === undefined || bottom === undefined) {
            throw new Error(`Missing mandatory bounding box coordinates (left, top, right, bottom) for text layer ${index}`);
        }

        const originalW = right - left;
        const originalH = bottom - top;

        const opt = calculateOptimalTextProperties(textContent, originalW, originalH);
        const fontSize = opt.fontSize;
        const boxWidth = opt.newW;
        const textHeight = opt.textHeight;

        const cx = left + originalW / 2;
        const cy = top + originalH / 2;

        const newLeft = cx - boxWidth / 2 + fontSize / 3;
        const newTop = cy - textHeight / 2 + fontSize;

        const strokeSize = txtConfig.strokeSize !== undefined ? txtConfig.strokeSize : 3;

        const layerName = textContent.length > 20 ? textContent.substring(0, 20) : (textContent || `Testo ${index}`);

        childrenNodes.push({
            name: layerName,
            left: newLeft,
            top: newTop,
            right: newLeft + boxWidth,
            bottom: newTop + textHeight,
            ...(strokeSize > 0 ? {
                effects: {
                    stroke: [{
                        enabled: true,
                        present: true,
                        showInDialog: true,
                        size: { value: strokeSize, units: 'Pixels' },
                        position: 'outside',
                        fillType: 'color',
                        blendMode: 'normal',
                        opacity: 1,
                        color: { r: 255, g: 255, b: 255, a: 1 }
                    }]
                }
            } : {}),
            text: {
                text: textContent, // Nessun wrapping manuale!
                shapeType: 'box', // Trasforma in Paragrafo nativo di Photoshop
                boxBounds: [0, 0, boxWidth, textHeight], // Formato: [Left, Top, Right, Bottom]
                transform: [1, 0, 0, 1, newLeft, newTop], // Offset globale del livello
                left: 0,
                top: 0,
                right: boxWidth,
                bottom: textHeight,
                style: {
                    font: { name: fontName },
                    fontSize: fontSize,
                    fillColor: txtConfig.color || { r: 0, g: 0, b: 0, a: 255 }
                },
                paragraphStyle: {
                    justification: 'center'
                }
            }
        });
    });

    const psdLayout = {
        width: canvasData.width,
        height: canvasData.height,
        children: childrenNodes
    };

    const buffer = writePsdBuffer(psdLayout);
    fs.writeFileSync(outputPath, buffer);
    console.log(`PSD generato con successo: ${outputPath}`);
}

const args = process.argv.slice(2);
if (args.length < 2) {
    console.error("Uso: psd-generator.exe <config.json> <output.psd>");
    process.exit(1);
}

generatePSD(args[0], args[1]).catch(err => {
    console.error("Errore critico:", err);
    process.exit(1);
});